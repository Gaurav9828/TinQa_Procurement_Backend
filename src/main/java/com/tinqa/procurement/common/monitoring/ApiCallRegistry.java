package com.tinqa.procurement.common.monitoring;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Per-endpoint call statistics. Logs a warning for requests over their SQL budget, with an N+1 pattern or slow
 * (at most once a minute per endpoint), and a periodic summary of the busiest endpoints so call volume can be watched
 * in every environment. With {@code monitoring.api.report-file} set (the test suite) the totals are written out when
 * the application stops, for the quality gate. The statements per request are also published as the
 * {@code api.db.statements} metric (/actuator/metrics/api.db.statements?tag=uri:/v1/...).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiCallRegistry {

    private static final long WARN_EVERY_MILLIS = 60_000;
    private static final int SUMMARY_TOP = 5;
    // Several test application contexts can share a JVM: the first one to stop starts the report, the others add to it
    private static final AtomicBoolean REPORT_STARTED = new AtomicBoolean();

    private final ApiMonitoringProperties properties;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final Map<String, EndpointStats> totals = new ConcurrentHashMap<>();
    private final Map<String, EndpointStats> window = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWarning = new ConcurrentHashMap<>();

    void record(String method, String route, int status, long millis, RequestDbStats.Snapshot db) {
        String endpoint = method + " " + route;
        boolean overBudget = db.statements() > properties.maxQueriesPerRequest();
        boolean nPlusOne = db.maxRepeat() >= properties.maxRepeatsOfOneStatement();
        boolean slow = millis >= properties.slowRequest().toMillis();
        totals.computeIfAbsent(endpoint, k -> new EndpointStats()).add(status, millis, db, overBudget, nPlusOne, slow);
        window.computeIfAbsent(endpoint, k -> new EndpointStats()).add(status, millis, db, overBudget, nPlusOne, slow);
        meterRegistry.ifAvailable(registry -> DistributionSummary.builder("api.db.statements")
                .description("SQL statements run by one API request")
                .tag("method", method).tag("uri", route)
                .register(registry)
                .record(db.statements()));
        if ((overBudget || nPlusOne || slow) && shouldWarn(endpoint)) {
            warnOverBudget(endpoint, millis, db, overBudget, nPlusOne);
        }
    }

    private void warnOverBudget(String endpoint, long millis, RequestDbStats.Snapshot db, boolean overBudget, boolean nPlusOne) {
        if (log.isWarnEnabled()) {
            String budget = overBudget ? "; budget is " + properties.maxQueriesPerRequest() : "";
            String repeated = nPlusOne ? "; likely N+1, the same statement ran " + db.maxRepeat() + " times: " + db.mostRepeated() : "";
            log.warn("API budget: {} took {} ms and ran {} SQL statement(s) ({} ms in the database){}{}", endpoint, millis,
                    db.statements(), db.dbMillis(), budget, repeated);
        }
    }

    private boolean shouldWarn(String endpoint) {
        long now = System.currentTimeMillis();
        Long previous = lastWarning.put(endpoint, now);
        return previous == null || now - previous >= WARN_EVERY_MILLIS;
    }

    /** The busiest endpoints since the last summary, by calls and by SQL statements. */
    @Scheduled(fixedDelayString = "${monitoring.api.summary-interval:PT15M}", initialDelayString = "${monitoring.api.summary-interval:PT15M}")
    void logSummary() {
        Map<String, EndpointStats.View> snapshot = new HashMap<>();
        window.forEach((endpoint, stats) -> snapshot.put(endpoint, stats.view()));
        window.clear();
        if (snapshot.isEmpty()) {
            return;
        }
        long calls = snapshot.values().stream().mapToLong(EndpointStats.View::calls).sum();
        long statements = snapshot.values().stream().mapToLong(EndpointStats.View::totalStatements).sum();
        if (log.isInfoEnabled()) {
            log.info("API calls since the last summary: {} call(s) to {} endpoint(s), {} SQL statement(s). Busiest: {}. Most SQL: {}",
                    calls, snapshot.size(), statements,
                    top(snapshot, Comparator.comparingLong(EndpointStats.View::calls)),
                    top(snapshot, Comparator.comparingLong(EndpointStats.View::totalStatements)));
        }
    }

    private static String top(Map<String, EndpointStats.View> stats, Comparator<EndpointStats.View> by) {
        return stats.entrySet().stream()
                .sorted(Map.Entry.<String, EndpointStats.View>comparingByValue(by).reversed())
                .limit(SUMMARY_TOP)
                .map(e -> e.getKey() + " ×" + e.getValue().calls() + " (avg " + e.getValue().avgStatements() + " SQL, "
                        + e.getValue().avgMillis() + " ms)")
                .collect(Collectors.joining(", "));
    }

    @PreDestroy
    void writeReport() throws IOException {
        if (properties.reportFile() == null || properties.reportFile().isBlank() || totals.isEmpty()) {
            return;
        }
        Path file = Path.of(properties.reportFile());
        List<String> lines = new ArrayList<>();
        boolean first = REPORT_STARTED.compareAndSet(false, true);
        if (first) {
            lines.add("# method\troute\tcalls\tavgStatements\tmaxStatements\tmaxRepeat\toverBudget\tnPlusOne\tslow\tserverErrors\tavgMillis\tbudget\trepeatLimit\trepeatedStatement");
        }
        totals.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            EndpointStats.View v = e.getValue().view();
            String[] methodRoute = e.getKey().split(" ", 2);
            lines.add(String.join("\t", methodRoute[0], methodRoute[1], String.valueOf(v.calls()), String.valueOf(v.avgStatements()),
                    String.valueOf(v.maxStatements()), String.valueOf(v.maxRepeat()), String.valueOf(v.overBudget()),
                    String.valueOf(v.nPlusOne()), String.valueOf(v.slow()), String.valueOf(v.serverErrors()), String.valueOf(v.avgMillis()),
                    String.valueOf(properties.maxQueriesPerRequest()), String.valueOf(properties.maxRepeatsOfOneStatement()),
                    v.repeatedStatement().replace('\t', ' ')));
        });
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                first ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
    }

    private static final class EndpointStats {
        private long calls;
        private long serverErrors;
        private long totalMillis;
        private long totalStatements;
        private int maxStatements;
        private int maxRepeat;
        private String repeatedStatement = "";
        private long overBudget;
        private long nPlusOne;
        private long slow;

        record View(long calls, long totalStatements, long avgStatements, int maxStatements, int maxRepeat, String repeatedStatement,
                    long overBudget, long nPlusOne, long slow, long serverErrors, long avgMillis) {
        }

        synchronized void add(int status, long millis, RequestDbStats.Snapshot db, boolean overBudget, boolean nPlusOne, boolean slow) {
            calls++;
            // Only 500 is a crash: 502/503/504 are deliberate answers about another system
            if (status == 500) {
                serverErrors++;
            }
            totalMillis += millis;
            totalStatements += db.statements();
            maxStatements = Math.max(maxStatements, db.statements());
            if (db.maxRepeat() > maxRepeat) {
                maxRepeat = db.maxRepeat();
                repeatedStatement = db.mostRepeated();
            }
            if (overBudget) {
                this.overBudget++;
            }
            if (nPlusOne) {
                this.nPlusOne++;
            }
            if (slow) {
                this.slow++;
            }
        }

        synchronized View view() {
            return new View(calls, totalStatements, calls == 0 ? 0 : Math.round((double) totalStatements / calls), maxStatements, maxRepeat,
                    repeatedStatement, overBudget, nPlusOne, slow, serverErrors, calls == 0 ? 0 : totalMillis / calls);
        }
    }
}
