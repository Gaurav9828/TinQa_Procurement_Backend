/*
 * Local quality gate (free replacement for SonarQube). Runs at the end of every `mvn package`
 * (see the quality plugins in pom.xml); needs only the JDK: `java quality/QualityGate.java`.
 *
 *   1. SpotBugs + FindSecBugs  (bugs and security vulnerabilities)      target/spotbugsXml.xml
 *   2. PMD                      (code smells, error-prone code)          target/pmd.xml
 *   3. Secret scan              (passwords, keys, tokens in code and config)
 *   4. Dependency vulnerabilities from OSV.dev                           target/quality/dependencies.txt
 *   5. Tests + line coverage    (Surefire + JaCoCo)
 *   6. API budgets              (per endpoint, from the test suite: SQL statements per call, N+1 queries, crashes)
 *                                                                         target/quality/api-calls.tsv
 *   7. New-code coverage        (the lines changed since the base branch that the tests run)
 *
 * Like Sonar's "new code" mode, existing SpotBugs/PMD/API findings live in quality/baseline.txt and only NEW
 * findings count. Local builds report new warnings, hard-coded secrets, low new-code coverage and skipped checks as
 * warnings so development keeps moving; strict builds (-Dquality.strict=true: UAT and production) fail on every one.
 *
 * Flags: --strict=true|false   --update-baseline=true|false   --skip-tests=true|false
 *        --tests=run|external  (external: the tests ran in an earlier build, e.g. before a docker image build)
 *        --build-start=<ISO instant>  (when the Maven build started; reports older than that are ignored)
 * Report: target/quality/index.html
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class QualityGate {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path TARGET = ROOT.resolve("target");
    static final Path REPORT_DIR = TARGET.resolve("quality");
    static final Path BASELINE = ROOT.resolve("quality/baseline.txt");
    static final Properties CONFIG = new Properties();
    static final boolean TTY = System.console() != null;

    record Issue(String tool, String rule, String category, String severity, String file, int line, String message,
                 String fingerprint, boolean baselinable) {
        boolean blocking(boolean strict) {
            if (tool.equals("secret")) return strict && severity.equals("error");
            return severity.equals("error") || category.equals("Security");
        }
    }

    record Vuln(String pkg, String severity, String id, String title, String fix, String acceptedBecause) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = new HashMap<>();
        for (String a : args) {
            String[] kv = a.replaceFirst("^--", "").split("=", 2);
            flags.put(kv[0], kv.length > 1 ? kv[1] : "true");
        }
        boolean strict = Boolean.parseBoolean(flags.getOrDefault("strict", "false"));
        boolean updateBaseline = Boolean.parseBoolean(flags.getOrDefault("update-baseline", "false"));
        boolean testsSkipped = Boolean.parseBoolean(flags.getOrDefault("skip-tests", "false"));
        boolean testsExternal = "external".equals(flags.getOrDefault("tests", "run"));
        if (flags.containsKey("build-start")) BUILD_START = Instant.parse(flags.get("build-start")).minusSeconds(5);
        try (var in = Files.newBufferedReader(ROOT.resolve("quality/quality-gate.properties"))) {
            CONFIG.load(in);
        }
        Files.createDirectories(REPORT_DIR);

        List<Issue> issues = new ArrayList<>();
        step("SpotBugs + FindSecBugs");
        issues.addAll(done(spotbugs()));
        step("PMD");
        issues.addAll(done(pmd()));
        step("Secret scan");
        List<Issue> secrets = secrets();
        issues.addAll(secrets);
        System.out.println(secrets.isEmpty() ? green("clean") : yellow(secrets.size() + " finding(s)"));
        step("API budgets (SQL per call, N+1, crashes)");
        List<Issue> apiIssues = testsSkipped ? List.of() : apiBudgets();
        issues.addAll(apiIssues);
        System.out.println(testsSkipped ? yellow("skipped") : apiIssues.isEmpty() ? green("within budget") : yellow(apiIssues.size() + " finding(s)"));
        issues.sort(Comparator.comparing(Issue::file).thenComparingInt(Issue::line));

        if (updateBaseline) {
            writeBaseline(issues);
            System.out.println(green("\nBaseline updated → quality/baseline.txt"));
            return;
        }

        step("Dependency vulnerabilities (OSV.dev)");
        String auditProblem = null;
        List<Vuln> vulns = List.of();
        try {
            vulns = dependencyVulns();
            System.out.println(vulns.isEmpty() ? green("none") : yellow(vulns.size() + " advisory(ies)"));
        } catch (Exception e) {
            auditProblem = "Dependency check not run: " + e.getMessage();
            System.out.println(yellow("unavailable"));
        }

        step("Tests + coverage");
        int[] tests = testsSkipped ? new int[]{0, 0, 0} : surefire(); // total, failed, skipped
        Double coverage = testsSkipped ? null : jacocoLineCoverage();
        System.out.println(tests[0] == 0 ? yellow("no test results (tests skipped?)")
                : (tests[0] - tests[1]) + "/" + tests[0] + " passed, coverage " + (coverage == null ? "n/a" : coverage + "%"));
        step("New-code coverage");
        NewCode newCode = testsSkipped ? null : newCodeCoverage();
        System.out.println(newCode == null ? yellow("n/a (no git history or no test run)")
                : newCode.percent() == null ? green("no changed executable lines")
                : newCode.covered() + "/" + newCode.total() + " changed lines tested (" + newCode.percent() + "%) since " + newCode.base());

        // ---------------------------------------------------------------- verdict
        Map<String, Integer> remaining = new HashMap<>(readBaseline());
        Set<Issue> newIssues = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Issue i : issues) {
            if (!i.baselinable()) continue;
            int left = remaining.getOrDefault(i.fingerprint(), 0);
            if (left > 0) remaining.put(i.fingerprint(), left - 1);
            else newIssues.add(i);
        }
        int fixed = remaining.values().stream().mapToInt(Integer::intValue).sum();

        Set<String> failOn = Set.of(CONFIG.getProperty("dependencies.failOn", "critical,high").split(","));
        double minNewCode = Double.parseDouble(CONFIG.getProperty("coverage.minNewCodePercent", "0"));
        List<Vuln> blockingVulns = vulns.stream().filter(v -> failOn.contains(v.severity()) && v.acceptedBecause() == null).toList();
        double minCoverage = Double.parseDouble(CONFIG.getProperty("coverage.minLinePercent", "0"));

        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long newBlocking = newIssues.stream().filter(i -> i.blocking(strict)).count();
        if (newBlocking > 0) reasons.add(newBlocking + " new issue(s) (errors or security findings) not in the baseline");
        List<Issue> secretErrors = secrets.stream().filter(s -> s.severity().equals("error")).toList();
        if (strict && !secretErrors.isEmpty()) reasons.add(secretErrors.size() + " hard-coded secret(s) (strict mode)");
        if (!blockingVulns.isEmpty()) reasons.add(blockingVulns.size() + " unaccepted " + String.join("/", failOn) + " dependency vulnerability(ies)");
        if (tests[1] > 0) reasons.add(tests[1] + " failing test(s)");
        if (coverage != null && coverage < minCoverage) reasons.add("line coverage " + coverage + "% is below " + minCoverage + "%");
        // Local builds only warn about these; strict (UAT/production) builds fail on every one
        List<String> strictOnly = new ArrayList<>();
        // Fixtures under src/test never ship; dev/local config files do (they are packaged into the jar)
        long secretWarnings = secrets.stream().filter(x -> !x.severity().equals("error") && !x.file().startsWith("src/test/")).count();
        if (secretWarnings > 0) strictOnly.add(secretWarnings + " hard-coded secret(s) in dev/local config files — move them to environment variables");
        if (!strict && !secretErrors.isEmpty()) strictOnly.add(secretErrors.size() + " hard-coded secret(s) in shared/production code or config");
        if (auditProblem != null) strictOnly.add(auditProblem);
        long newNonBlocking = newIssues.size() - newBlocking;
        long newApi = newIssues.stream().filter(i -> i.tool().equals("api")).count();
        if (newNonBlocking > 0) strictOnly.add(newNonBlocking + " new warning(s)" + (newApi > 0 ? ", " + newApi + " of them API budget finding(s)" : ""));
        if (tests[0] == 0 && !testsExternal) strictOnly.add("No test results: tests were skipped, so behaviour, API budgets and coverage were not checked");
        if (newCode != null && newCode.percent() != null && newCode.percent() < minNewCode)
            strictOnly.add("new-code coverage " + newCode.percent() + "% is below " + minNewCode + "% — add tests for: " + newCode.worstFiles());
        List<Vuln> moderateVulns = vulns.stream().filter(v -> v.severity().equals("moderate") && !failOn.contains("moderate") && v.acceptedBecause() == null).toList();
        if (!moderateVulns.isEmpty()) strictOnly.add(moderateVulns.size() + " unaccepted moderate dependency vulnerability(ies)");
        if (strict) reasons.addAll(strictOnly);
        else strictOnly.forEach(w -> warnings.add(w + " — allowed locally, fails the strict (UAT/production) build"));
        if (fixed > 0) warnings.add(fixed + " baseline finding(s) fixed — run `mvn package -Dquality.baseline=true` to lock that in");
        if (tests[0] == 0 && testsExternal) warnings.add("Tests not run in this build (-Dquality.tests=external): they must have passed in an earlier `mvn package`");

        boolean passed = reasons.isEmpty();
        writeHtml(passed, strict, reasons, warnings, issues, newIssues, fixed, vulns, blockingVulns.size(), tests, coverage, minCoverage,
                newCode, minNewCode);

        System.out.println();
        for (Issue s : secrets)
            System.out.println("  " + (s.severity().equals("error") ? red("SECRET ERROR") : yellow("SECRET WARNING")) + " " + s.file() + ":" + s.line() + " " + s.message());
        newIssues.stream().filter(i -> i.blocking(strict)).limit(30).forEach(i ->
                System.out.println("  " + red("NEW") + " " + i.category() + " " + i.file() + ":" + i.line() + " " + dim(i.rule()) + " " + i.message()));
        if (newBlocking > 30) System.out.println(dim("  …and " + (newBlocking - 30) + " more"));
        for (Vuln v : blockingVulns) System.out.println("  " + red("VULN") + " " + v.severity() + " " + v.pkg() + ": " + v.title() + dim(" (" + v.fix() + ")"));
        for (String w : warnings) System.out.println("  " + yellow("!") + " " + w);
        System.out.println("\n" + bold("Quality gate" + (strict ? " (strict)" : "") + ": ") + (passed ? green("PASSED") : red("FAILED"))
                + "  " + dim("report → target/quality/index.html"));
        for (String r : reasons) System.out.println("  " + red("✗") + " " + r);
        if (!passed) System.exit(1);
    }

    // ---------------------------------------------------------------- SpotBugs

    static List<Issue> spotbugs() throws Exception {
        Path xml = TARGET.resolve("spotbugsXml.xml");
        if (!Files.exists(xml)) return List.of();
        List<Issue> out = new ArrayList<>();
        Document doc = parseXml(xml);
        NodeList bugs = doc.getElementsByTagName("BugInstance");
        for (int i = 0; i < bugs.getLength(); i++) {
            Element bug = (Element) bugs.item(i);
            Element src = directChild(bug, "SourceLine");
            String sourcePath = src == null ? "" : src.getAttribute("sourcepath");
            String file = sourceFile(sourcePath);
            int line = src == null || src.getAttribute("start").isEmpty() ? 0 : Integer.parseInt(src.getAttribute("start"));
            String cat = bug.getAttribute("category");
            String category = switch (cat) {
                case "SECURITY", "MALICIOUS_CODE" -> "Security";
                case "CORRECTNESS", "MT_CORRECTNESS", "BAD_PRACTICE", "PERFORMANCE" -> "Reliability";
                default -> "Maintainability";
            };
            Element longMsg = directChild(bug, "LongMessage");
            String message = longMsg != null ? longMsg.getTextContent() : bug.getAttribute("type");
            Element method = directChild(bug, "Method");
            String anchor = line > 0 ? lineText(file, line) : (method != null ? method.getAttribute("name") : "") + message;
            out.add(new Issue("spotbugs", "spotbugs/" + bug.getAttribute("type"), category, "error", file, line, message,
                    "spotbugs/" + bug.getAttribute("type") + "|" + file + "|" + hash(anchor), true));
        }
        return out;
    }

    static String sourceFile(String sourcePath) {
        for (String dir : List.of("src/main/java/", "src/main/kotlin/")) {
            if (Files.exists(ROOT.resolve(dir + sourcePath))) return dir + sourcePath;
        }
        return sourcePath;
    }

    // ---------------------------------------------------------------- PMD

    static List<Issue> pmd() throws Exception {
        Path xml = TARGET.resolve("pmd.xml");
        if (!Files.exists(xml)) return List.of();
        List<Issue> out = new ArrayList<>();
        Document doc = parseXml(xml);
        NodeList files = doc.getElementsByTagName("file");
        for (int f = 0; f < files.getLength(); f++) {
            Element fileEl = (Element) files.item(f);
            String file = rel(Path.of(fileEl.getAttribute("name")));
            NodeList violations = fileEl.getElementsByTagName("violation");
            for (int v = 0; v < violations.getLength(); v++) {
                Element el = (Element) violations.item(v);
                int line = Integer.parseInt(el.getAttribute("beginline"));
                String rule = "pmd/" + el.getAttribute("rule");
                String ruleset = el.getAttribute("ruleset");
                String category = switch (ruleset) {
                    case "Security" -> "Security";
                    case "Error Prone", "Multithreading" -> "Reliability";
                    default -> "Maintainability";
                };
                String severity = Integer.parseInt(el.getAttribute("priority")) <= 2 ? "error" : "warning";
                out.add(new Issue("pmd", rule, category, severity, file, line, el.getTextContent().trim(),
                        rule + "|" + file + "|" + hash(lineText(file, line)), true));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- secrets

    static final List<String[]> TOKEN_PATTERNS = List.of(
            new String[]{"private-key", "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY", "Private key committed to the repository"},
            new String[]{"aws-access-key", "\\b(AKIA|ASIA)[0-9A-Z]{16}\\b", "AWS access key id"},
            new String[]{"google-api-key", "\\bAIza[0-9A-Za-z_-]{35}\\b", "Google API key"},
            new String[]{"github-token", "\\b(ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{20,}\\b", "GitHub token"},
            new String[]{"slack-token", "\\bxox[abposr]-[A-Za-z0-9-]{10,}\\b", "Slack token"},
            new String[]{"stripe-live-key", "\\b(sk|rk)_live_[A-Za-z0-9]{16,}\\b", "Stripe live secret key"},
            new String[]{"razorpay-key", "\\brzp_live_[A-Za-z0-9]{10,}\\b", "Razorpay live key"},
            new String[]{"jwt", "\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}", "JSON Web Token committed to the repository"},
            new String[]{"credentials-in-url", "(?i)[a-z][a-z0-9+.-]*://[^/\\s:@'\"$]+:[^/\\s:@'\"$]{3,}@", "Username/password embedded in a URL"});
    /** Config keys whose value is a credential: last key segment ends in password/secret/token/api-key/…, or is exactly "key". */
    static final Pattern CONFIG_KEY = Pattern.compile(
            "^\\s*([\\w.\\-\\[\\]]*?)([\\w\\-]*(password|passwd|pwd|secret|token|api-?key|private-?key|access-?key)|key)\\s*[:=]\\s*(.*?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    static final Pattern CODE_LITERAL = Pattern.compile(
            "(?i)\\b(\\w*(password|passwd|secret|apikey|api_key|accesstoken|authtoken|privatekey)\\w*)\\s*=\\s*\"([^\"\\s]{6,})\"");
    /** Constants that hold the NAME of a credential, not the credential: API_KEY_HEADER, PASSWORD_PARAM, … */
    static final Pattern NAME_CONSTANT = Pattern.compile("(?i).*(header|name|param|field|path|url|uri|code|message|label|prefix|endpoint|property|attribute|key_?id)$");
    /** Constant names, URL paths, property keys and placeholders: e.g. PASSWORD_CHANGED, /auth/change-password, tinqa.jwt.secret */
    static final Pattern NOT_A_SECRET = Pattern.compile("[A-Z][A-Z0-9_]*|/.*|[a-z][a-z0-9-]*(\\.[a-zA-Z0-9_-]+)+|\\$\\{.*}|%s.*");
    static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::(.*))?}$");
    static final Pattern TEXT_FILE = Pattern.compile("\\.(java|kt|kts|ya?ml|properties|xml|json|sql|sh|env|conf|txt|md|html)$|(^|/)\\.env[^/]*$|Dockerfile$");

    static List<Issue> secrets() throws IOException {
        Pattern ignore = Pattern.compile(CONFIG.getProperty("secrets.ignorePaths", "^$"));
        Pattern localOnly = Pattern.compile(CONFIG.getProperty("secrets.localOnlyPaths", "^$"));
        List<Map.Entry<String[], Pattern>> tokens = TOKEN_PATTERNS.stream().map(p -> Map.entry(p, Pattern.compile(p[1]))).toList();
        List<Issue> out = new ArrayList<>();
        for (String file : repoFiles()) {
            if (!TEXT_FILE.matcher(file).find() || ignore.matcher(file).find()) continue;
            Path abs = ROOT.resolve(file);
            if (!Files.isRegularFile(abs) || Files.size(abs) > 2_000_000) continue;
            List<String> lines;
            try {
                lines = Files.readAllLines(abs, StandardCharsets.UTF_8);
            } catch (IOException notUtf8) {
                continue;
            }
            String severity = localOnly.matcher(file).find() ? "warning" : "error";
            boolean config = file.matches(".*\\.(ya?ml|properties|env)$|.*(^|/)\\.env[^/]*$");
            boolean code = file.matches(".*\\.(java|kt|kts)$");
            for (int n = 0; n < lines.size(); n++) {
                String text = lines.get(n);
                if (text.trim().startsWith("#") || text.trim().startsWith("//")) continue;
                for (var t : tokens) {
                    if (t.getValue().matcher(text).find()) out.add(secret(t.getKey()[0], severity, file, n + 1, t.getKey()[2], text));
                }
                if (config) {
                    Matcher m = CONFIG_KEY.matcher(text);
                    if (m.matches()) {
                        String problem = configValueProblem(stripQuotes(m.group(4)));
                        if (problem != null) out.add(secret("hard-coded-credential", severity, file, n + 1, problem + " for '" + m.group(1) + m.group(2) + "'", text));
                    }
                }
                if (code) {
                    Matcher m = CODE_LITERAL.matcher(text);
                    if (m.find() && !NAME_CONSTANT.matcher(m.group(1)).matches() && !NOT_A_SECRET.matcher(m.group(3)).matches())
                        out.add(secret("hard-coded-credential", severity, file, n + 1, "Hard-coded credential in code", text));
                }
            }
        }
        return out;
    }

    /** Null when the value is empty or comes from the environment / another property. */
    static String configValueProblem(String value) {
        if (value.isEmpty() || value.equals("|") || value.equals(">")) return null;
        Matcher p = PLACEHOLDER.matcher(value);
        if (p.matches()) {
            String fallback = p.group(2);
            if (fallback == null || fallback.isEmpty() || fallback.startsWith("${")) return null;
            return "Hard-coded fallback value";
        }
        if (value.contains("${")) return null;
        if (value.length() < 4 || value.matches("(?i)true|false|null|none|changeme|\\d+[smhd]?")) return null;
        return "Hard-coded credential";
    }

    static String stripQuotes(String v) {
        return v.replaceAll("\\s+#.*$", "").replaceAll("^['\"]|['\"]$", "");
    }

    static Issue secret(String id, String severity, String file, int line, String message, String text) {
        return new Issue("secret", "secret/" + id, "Security", severity, file, line, message, "secret/" + id + "|" + file + "|" + hash(text.trim()), false);
    }

    static List<String> repoFiles() throws IOException {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "-co", "--exclude-standard").directory(ROOT.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() == 0) return out.lines().filter(s -> !s.isBlank()).toList();
        } catch (IOException | InterruptedException noGit) {
            // e.g. inside a docker build: walk the tree instead
        }
        Set<String> skip = Set.of("target", ".git", ".idea", ".mvn", "uploads", "data", "node_modules");
        try (Stream<Path> walk = Files.walk(ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> ROOT.relativize(f).toString().split("[/\\\\]").length > 0 && !skip.contains(ROOT.relativize(f).getName(0).toString()))
                    .map(QualityGate::rel).toList();
        }
    }

    // ---------------------------------------------------------------- dependencies (OSV.dev)

    static final Pattern DEP_LINE = Pattern.compile("^\\s+([\\w.\\-]+):([\\w.\\-]+):[\\w.\\-]+(?::[\\w.\\-]+)?:([\\w.\\-+]+):(compile|runtime|provided|test|system|import)\\b");

    static List<Vuln> dependencyVulns() throws Exception {
        Path list = REPORT_DIR.resolve("dependencies.txt");
        if (!Files.exists(list)) throw new IOException("target/quality/dependencies.txt missing");
        Map<String, String[]> deps = new LinkedHashMap<>(); // g:a@v -> {g:a, v, scope}
        for (String line : Files.readAllLines(list)) {
            Matcher m = DEP_LINE.matcher(line);
            if (m.find()) deps.putIfAbsent(m.group(1) + ":" + m.group(2) + "@" + m.group(3), new String[]{m.group(1) + ":" + m.group(2), m.group(3), m.group(4)});
        }
        int timeout = Integer.parseInt(CONFIG.getProperty("dependencies.timeoutSeconds", "60"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout)).build();
        List<String[]> depList = new ArrayList<>(deps.values());
        List<Map.Entry<String[], String>> hits = new ArrayList<>();
        for (int i = 0; i < depList.size(); i += 500) {
            List<String[]> chunk = depList.subList(i, Math.min(depList.size(), i + 500));
            String body = chunk.stream()
                    .map(d -> "{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"" + d[0] + "\"},\"version\":\"" + d[1] + "\"}")
                    .collect(Collectors.joining(",", "{\"queries\":[", "]}"));
            Map<?, ?> res = (Map<?, ?>) Json.parse(post(http, "https://api.osv.dev/v1/querybatch", body, timeout));
            List<?> results = (List<?>) res.get("results");
            for (int j = 0; j < results.size(); j++) {
                Object vs = ((Map<?, ?>) results.get(j)).get("vulns");
                if (vs instanceof List<?> l) for (Object v : l) hits.add(Map.entry(chunk.get(j), (String) ((Map<?, ?>) v).get("id")));
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        Map<String, Future<Map<?, ?>>> details = new HashMap<>();
        for (var h : hits) details.computeIfAbsent(h.getValue(), id -> pool.submit(() -> (Map<?, ?>) Json.parse(get(http, "https://api.osv.dev/v1/vulns/" + id, timeout))));
        pool.shutdown();

        Map<String, String> allowed = new HashMap<>();
        String today = LocalDate.now().toString();
        for (String key : CONFIG.stringPropertyNames()) {
            if (!key.startsWith("dependencies.allow.")) continue;
            String[] untilReason = CONFIG.getProperty(key).split("\\|", 2);
            if (untilReason[0].isBlank() || untilReason[0].compareTo(today) >= 0)
                allowed.put(key.substring("dependencies.allow.".length()), untilReason.length > 1 ? untilReason[1] : "accepted");
        }
        List<Vuln> out = new ArrayList<>();
        for (var h : hits) {
            Map<?, ?> v = details.get(h.getValue()).get(timeout, TimeUnit.SECONDS);
            String[] dep = h.getKey();
            Map<?, ?> dbSpecific = (Map<?, ?>) v.get("database_specific");
            String severity = String.valueOf(dbSpecific != null && dbSpecific.get("severity") != null ? dbSpecific.get("severity") : "moderate").toLowerCase();
            if (severity.equals("medium")) severity = "moderate";
            List<String> fixedVersions = new ArrayList<>();
            if (v.get("affected") instanceof List<?> affected) for (Object a : affected) {
                Map<?, ?> am = (Map<?, ?>) a;
                if (!(am.get("package") instanceof Map<?, ?> pkg) || !dep[0].equals(pkg.get("name"))) continue;
                if (am.get("ranges") instanceof List<?> ranges) for (Object r : ranges)
                    for (Object e : (List<?>) ((Map<?, ?>) r).get("events")) if (((Map<?, ?>) e).get("fixed") != null) fixedVersions.add((String) ((Map<?, ?>) e).get("fixed"));
            }
            List<String> ids = new ArrayList<>(List.of((String) v.get("id")));
            if (v.get("aliases") instanceof List<?> al) al.forEach(a -> ids.add((String) a));
            String accepted = ids.stream().map(allowed::get).filter(Objects::nonNull).findFirst().orElse(null);
            out.add(new Vuln(dep[0] + ":" + dep[1] + ("test".equals(dep[2]) ? " (test)" : ""), severity, (String) v.get("id"),
                    String.valueOf(v.get("summary") != null ? v.get("summary") : v.get("id")),
                    fixedVersions.isEmpty() ? "no fix yet" : "upgrade to " + String.join(" / ", fixedVersions), accepted));
        }
        return out;
    }

    static String post(HttpClient http, String url, String body, int timeout) throws Exception {
        return send(http, HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout))
                .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    static String get(HttpClient http, String url, int timeout) throws Exception {
        return send(http, HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout)).GET().build());
    }

    static String send(HttpClient http, HttpRequest req) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("OSV.dev returned " + res.statusCode());
        return res.body();
    }

    // ---------------------------------------------------------------- tests + coverage

    static int[] surefire() throws Exception {
        Path dir = TARGET.resolve("surefire-reports");
        int total = 0, failed = 0, skipped = 0;
        if (!Files.isDirectory(dir)) return new int[]{0, 0, 0};
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                // Reports older than this build's classes are leftovers from a previous run
                if (Files.getLastModifiedTime(p).toInstant().isBefore(buildStart())) continue;
                Element suite = parseXml(p).getDocumentElement();
                total += intAttr(suite, "tests");
                failed += intAttr(suite, "failures") + intAttr(suite, "errors");
                skipped += intAttr(suite, "skipped");
            }
        }
        return new int[]{total, failed, skipped};
    }

    // ---------------------------------------------------------------- API budgets

    /**
     * Endpoints that went over their SQL budget, ran one statement per row (N+1) or crashed (500) during the test suite,
     * as recorded by the application's API monitor (common/monitoring). Baselined like code findings.
     */
    static List<Issue> apiBudgets() throws IOException {
        Path tsv = REPORT_DIR.resolve("api-calls.tsv");
        if (!Files.exists(tsv) || Files.getLastModifiedTime(tsv).toInstant().isBefore(buildStart())) return List.of();
        // Several application contexts may report the same endpoint: keep the worst of each
        Map<String, String[]> worst = new TreeMap<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            if (c.length < 14) continue;
            worst.merge(c[0] + " " + c[1], c, (a, b) -> {
                String[] m = a.clone();
                for (int i : new int[]{4, 5, 6, 7, 9}) m[i] = String.valueOf(Math.max(Long.parseLong(a[i]), Long.parseLong(b[i])));
                if (Long.parseLong(b[5]) > Long.parseLong(a[5])) m[13] = b[13];
                return m;
            });
        }
        List<Issue> out = new ArrayList<>();
        worst.forEach((endpoint, c) -> {
            if (Long.parseLong(c[6]) > 0)
                out.add(api("too-many-queries", "Performance", endpoint, "up to " + c[4] + " SQL statements in one call (budget " + c[11] + ")"));
            if (Long.parseLong(c[7]) > 0)
                out.add(api("n-plus-one", "Performance", endpoint, "the same statement ran " + c[5] + " times in one call (N+1, limit " + c[12] + "): " + c[13]));
            if (Long.parseLong(c[9]) > 0)
                out.add(api("crash", "Reliability", endpoint, "answered 500 in " + c[9] + " call(s): bad input must get a 4xx with a reason"));
        });
        return out;
    }

    static Issue api(String rule, String category, String endpoint, String message) {
        return new Issue("api", "api/" + rule, category, "warning", endpoint, 0, message, "api/" + rule + "|" + endpoint, true);
    }

    // ---------------------------------------------------------------- new-code coverage

    record NewCode(String base, int covered, int total, Double percent, String worstFiles) {}

    static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");
    static final Pattern JACOCO_TOKEN = Pattern.compile(
            "<package name=\"([^\"]*)\"|<sourcefile name=\"([^\"]*)\"|<line nr=\"(\\d+)\" mi=\"(\\d+)\" ci=\"(\\d+)\"");

    /**
     * Of the source lines added or changed since the base branch (committed, uncommitted and untracked), how many the
     * tests ran. Only executable lines count (JaCoCo's line data). Null without git history or a JaCoCo report.
     */
    static NewCode newCodeCoverage() throws IOException {
        Path xml = TARGET.resolve("site/jacoco/jacoco.xml");
        if (!Files.exists(xml) || Files.getLastModifiedTime(xml).toInstant().isBefore(buildStart())) return null;
        String base = null;
        String baseRef = null;
        for (String ref : CONFIG.getProperty("newCode.baseRefs", "origin/UAT,UAT,origin/main,main").split(",")) {
            String mergeBase = git("merge-base", "HEAD", ref.trim());
            if (mergeBase != null && !mergeBase.isBlank()) {
                base = mergeBase.trim();
                baseRef = ref.trim();
                break;
            }
        }
        if (base == null) return null;

        Map<String, Set<Integer>> changed = new TreeMap<>();
        String diff = git("diff", "-U0", "--no-color", "--no-ext-diff", base, "--", "src/main");
        String file = null;
        for (String line : diff == null ? List.<String>of() : diff.lines().toList()) {
            if (line.startsWith("+++ ")) {
                file = line.startsWith("+++ b/") ? line.substring(6) : null;
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (file != null && hunk.find()) {
                int start = Integer.parseInt(hunk.group(1));
                int count = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                for (int n = start; n < start + count; n++) changed.computeIfAbsent(file, f -> new TreeSet<>()).add(n);
            }
        }
        String untracked = git("ls-files", "-o", "--exclude-standard", "--", "src/main");
        for (String f : untracked == null ? List.<String>of() : untracked.lines().filter(l -> !l.isBlank()).toList()) {
            try (Stream<String> lines = Files.lines(ROOT.resolve(f), StandardCharsets.UTF_8)) {
                int count = (int) lines.count();
                for (int n = 1; n <= count; n++) changed.computeIfAbsent(f, k -> new TreeSet<>()).add(n);
            } catch (IOException | java.io.UncheckedIOException notText) {
                // a binary resource: no lines to cover
            }
        }

        // sourcefile path (com/tinqa/…/Foo.java) -> line number -> run by a test
        Map<String, Map<Integer, Boolean>> lines = new HashMap<>();
        Matcher m = JACOCO_TOKEN.matcher(Files.readString(xml, StandardCharsets.UTF_8));
        String pkg = "";
        Map<Integer, Boolean> current = null;
        while (m.find()) {
            if (m.group(1) != null) pkg = m.group(1);
            else if (m.group(2) != null) current = lines.computeIfAbsent(pkg + "/" + m.group(2), k -> new HashMap<>());
            else if (current != null) current.put(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(5)) > 0);
        }

        int covered = 0, total = 0;
        Map<String, Integer> uncoveredByFile = new TreeMap<>();
        for (var e : changed.entrySet()) {
            Map<Integer, Boolean> fileLines = lines.get(e.getKey().replaceFirst("^src/main/(java|kotlin)/", ""));
            if (fileLines == null) continue; // not compiled code (resources, migrations)
            for (int n : e.getValue()) {
                Boolean hit = fileLines.get(n);
                if (hit == null) continue;
                total++;
                if (hit) covered++;
                else uncoveredByFile.merge(e.getKey().substring(e.getKey().lastIndexOf('/') + 1), 1, Integer::sum);
            }
        }
        String worst = uncoveredByFile.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
                .map(e -> e.getKey() + " (" + e.getValue() + " lines)").collect(Collectors.joining(", "));
        return new NewCode(baseRef, covered, total, total == 0 ? null : Math.round(covered * 1000.0 / total) / 10.0, worst);
    }

    /** Output of a git command, or null when git is missing or the command fails. */
    static String git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(ROOT.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Set by Maven (--build-start); an IDE compiling in the background must not make this build's reports look stale. */
    static Instant BUILD_START;

    static Instant buildStart() throws IOException {
        if (BUILD_START != null) return BUILD_START;
        Path classes = TARGET.resolve("classes");
        return Files.exists(classes) ? Files.getLastModifiedTime(classes).toInstant().minusSeconds(5) : Instant.EPOCH;
    }

    static Double jacocoLineCoverage() throws IOException {
        Path csv = TARGET.resolve("site/jacoco/jacoco.csv");
        if (!Files.exists(csv) || Files.getLastModifiedTime(csv).toInstant().isBefore(buildStart())) return null;
        long missed = 0, covered = 0;
        for (String line : Files.readAllLines(csv).subList(1, Files.readAllLines(csv).size())) {
            String[] c = line.split(",");
            missed += Long.parseLong(c[7]);
            covered += Long.parseLong(c[8]);
        }
        return missed + covered == 0 ? null : Math.round(covered * 1000.0 / (missed + covered)) / 10.0;
    }

    // ---------------------------------------------------------------- baseline

    static Map<String, Integer> readBaseline() throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        if (!Files.exists(BASELINE)) return counts;
        for (String line : Files.readAllLines(BASELINE)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", 2);
            counts.put(parts[1], Integer.parseInt(parts[0]));
        }
        return counts;
    }

    static void writeBaseline(List<Issue> issues) throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        issues.stream().filter(Issue::baselinable).forEach(i -> counts.merge(i.fingerprint(), 1, Integer::sum));
        List<String> lines = new ArrayList<>(List.of("# Accepted existing findings (count<TAB>fingerprint). Regenerate: mvn package -Dquality.baseline=true"));
        counts.forEach((k, v) -> lines.add(v + "\t" + k));
        Files.write(BASELINE, lines);
    }

    // ---------------------------------------------------------------- HTML report

    static void writeHtml(boolean passed, boolean strict, List<String> reasons, List<String> warnings, List<Issue> issues, Set<Issue> newIssues,
                          int fixed, List<Vuln> vulns, int blockingVulns, int[] tests, Double coverage, double minCoverage,
                          NewCode newCode, double minNewCode) throws IOException {
        Map<String, Long> byCat = issues.stream().collect(Collectors.groupingBy(Issue::category, Collectors.counting()));
        long secretCount = issues.stream().filter(i -> i.tool().equals("secret")).count();
        StringBuilder h = new StringBuilder();
        h.append("""
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Quality Gate Report</title><style>
                :root{--bg:#f7f7f8;--fg:#1d1d1f;--muted:#6b6b70;--card:#fff;--line:#e3e3e6;--ok:#1a7f37;--bad:#cf222e;--warn:#9a6700;--newbg:#fff1f0}
                @media (prefers-color-scheme:dark){:root{--bg:#141416;--fg:#ececf0;--muted:#9a9aa2;--card:#1d1d21;--line:#2e2e34;--ok:#3fb950;--bad:#f85149;--warn:#d29922;--newbg:#3a1d1d}}
                body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,sans-serif}main{max-width:1200px;margin:0 auto;padding:24px 16px}
                h1{margin:0 0 4px}.status{font-size:20px;font-weight:700}.pass{color:var(--ok)}.fail{color:var(--bad)}.muted{color:var(--muted)}
                .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:20px 0}
                .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px}.card .v{font-size:24px;font-weight:700}.card .l{color:var(--muted)}
                .card.bad .v{color:var(--bad)}.card.ok .v{color:var(--ok)}.card.warn .v{color:var(--warn)}
                ul.reasons{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 28px}
                .tbl{overflow-x:auto;background:var(--card);border:1px solid var(--line);border-radius:10px;margin-bottom:24px}
                table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 10px;border-bottom:1px solid var(--line);vertical-align:top}
                tr.new{background:var(--newbg)}code{font-size:12px}a{color:inherit}.filters{margin:8px 0;display:flex;gap:8px;flex-wrap:wrap}
                select,label{font:inherit;color:var(--fg);background:var(--card);border:1px solid var(--line);border-radius:6px;padding:4px 8px}
                </style></head><body><main><h1>Quality Gate</h1>""");
        h.append("<div class=\"muted\">").append(esc(Instant.now().toString())).append(strict ? " · strict mode (UAT/production)" : " · local mode").append("</div>");
        h.append("<p class=\"status ").append(passed ? "pass\">PASSED" : "fail\">FAILED").append("</p>");
        if (!reasons.isEmpty()) h.append("<ul class=\"reasons\">").append(reasons.stream().map(r -> "<li>" + esc(r) + "</li>").collect(Collectors.joining())).append("</ul>");
        if (!warnings.isEmpty()) h.append("<ul class=\"reasons\">").append(warnings.stream().map(r -> "<li class=\"muted\">" + esc(r) + "</li>").collect(Collectors.joining())).append("</ul>");
        h.append("<div class=\"cards\">")
                .append(card("New issues", String.valueOf(newIssues.size()), newIssues.isEmpty() ? "ok" : "bad"))
                .append(card("Hard-coded secrets", String.valueOf(secretCount), secretCount > 0 ? "warn" : "ok"))
                .append(card("Security issues", String.valueOf(byCat.getOrDefault("Security", 0L)), byCat.containsKey("Security") ? "warn" : "ok"))
                .append(card("Reliability issues", String.valueOf(byCat.getOrDefault("Reliability", 0L)), ""))
                .append(card("Maintainability issues", String.valueOf(byCat.getOrDefault("Maintainability", 0L)), ""))
                .append(card("Fixed since baseline", String.valueOf(fixed), fixed > 0 ? "ok" : ""))
                .append(card("Vulnerable deps (high+)", String.valueOf(blockingVulns), blockingVulns > 0 ? "bad" : "ok"))
                .append(card("Failed tests", tests[0] == 0 ? "skipped" : String.valueOf(tests[1]), tests[1] > 0 ? "bad" : "ok"))
                .append(card("Line coverage", coverage == null ? "n/a" : coverage + "%", coverage != null && coverage < minCoverage ? "bad" : "ok"))
                .append(card("New-code coverage", newCode == null || newCode.percent() == null ? "n/a" : newCode.percent() + "%",
                        newCode != null && newCode.percent() != null && newCode.percent() < minNewCode ? "warn" : "ok"))
                .append(card("API budget findings", String.valueOf(issues.stream().filter(i -> i.tool().equals("api")).count()),
                        issues.stream().anyMatch(i -> i.tool().equals("api")) ? "warn" : "ok"))
                .append("</div>");
        if (!vulns.isEmpty()) {
            h.append("<h2>Dependency vulnerabilities</h2><div class=\"tbl\"><table><tr><th>Severity</th><th>Package</th><th>Advisory</th><th>Fix</th><th>Accepted because</th></tr>");
            for (Vuln v : vulns)
                h.append("<tr class=\"").append(v.acceptedBecause() == null ? "new" : "").append("\"><td>").append(esc(v.severity())).append("</td><td><code>").append(esc(v.pkg()))
                        .append("</code></td><td><a href=\"https://osv.dev/vulnerability/").append(esc(v.id())).append("\">").append(esc(v.title())).append("</a></td><td>")
                        .append(esc(v.fix())).append("</td><td>").append(esc(v.acceptedBecause() == null ? "" : v.acceptedBecause())).append("</td></tr>");
            h.append("</table></div>");
        }
        h.append("<h2>Code issues (").append(issues.size()).append(")</h2>")
                .append("<div class=\"filters\"><select id=\"cat\"><option value=\"\">All categories</option><option>Security</option><option>Reliability</option><option>Performance</option><option>Maintainability</option></select>")
                .append("<label><input type=\"checkbox\" id=\"onlyNew\"> Only new</label></div>")
                .append("<div class=\"tbl\"><table id=\"issues\"><tr><th></th><th>Category</th><th>Severity</th><th>Rule</th><th>Location</th><th>Message</th></tr>");
        for (Issue i : issues) {
            boolean isNew = newIssues.contains(i) || i.tool().equals("secret");
            h.append("<tr class=\"").append(isNew ? "new" : "").append("\" data-cat=\"").append(i.category()).append("\" data-new=\"").append(isNew).append("\"><td>")
                    .append(i.tool().equals("secret") ? "<b>SECRET</b>" : newIssues.contains(i) ? "<b>NEW</b>" : "").append("</td><td>").append(i.category()).append("</td><td>")
                    .append(i.severity()).append("</td><td><code>").append(esc(i.rule())).append("</code></td><td><code>").append(esc(i.file())).append(i.line() > 0 ? ":" + i.line() : "")
                    .append("</code></td><td>").append(esc(i.message())).append("</td></tr>");
        }
        h.append("""
                </table></div><script>
                const cat=document.getElementById('cat'),onlyNew=document.getElementById('onlyNew');
                function f(){document.querySelectorAll('#issues tr[data-cat]').forEach(tr=>{tr.hidden=(cat.value&&tr.dataset.cat!==cat.value)||(onlyNew.checked&&tr.dataset.new!=='true')})}
                cat.onchange=f;onlyNew.onchange=f;
                </script></main></body></html>""");
        Files.writeString(REPORT_DIR.resolve("index.html"), h);
    }

    static String card(String label, String value, String state) {
        return "<div class=\"card " + state + "\"><div class=\"v\">" + esc(value) + "</div><div class=\"l\">" + esc(label) + "</div></div>";
    }

    // ---------------------------------------------------------------- helpers

    static Document parseXml(Path file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(file.toFile());
    }

    static Element directChild(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) return e;
        }
        return null;
    }

    static int intAttr(Element e, String name) {
        String v = e.getAttribute(name);
        return v.isEmpty() ? 0 : (int) Double.parseDouble(v);
    }

    static final Map<String, List<String>> SOURCE_CACHE = new HashMap<>();

    static String lineText(String file, int line) {
        List<String> lines = SOURCE_CACHE.computeIfAbsent(file, f -> {
            try {
                return Files.readAllLines(ROOT.resolve(f));
            } catch (IOException e) {
                return List.of();
            }
        });
        return line > 0 && line <= lines.size() ? lines.get(line - 1).trim() : "";
    }

    static String rel(Path p) {
        return ROOT.relativize(p.toAbsolutePath()).toString().replace('\\', '/');
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static void step(String name) {
        System.out.print(dim("›") + " " + name + "… ");
    }

    static List<Issue> done(List<Issue> found) {
        System.out.println(found.size() + " finding(s)");
        return found;
    }

    static String ansi(String code, String s) {
        return TTY ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }

    static String red(String s) { return ansi("31", s); }
    static String green(String s) { return ansi("32", s); }
    static String yellow(String s) { return ansi("33", s); }
    static String bold(String s) { return ansi("1", s); }
    static String dim(String s) { return ansi("2", s); }

    /** Minimal JSON reader for OSV responses (objects → Map, arrays → List, strings, numbers, booleans, null). */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) { return new Json(s).value(); }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(start, i));
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // :
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}') return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']') return l;
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
