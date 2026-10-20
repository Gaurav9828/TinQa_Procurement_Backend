package com.tinqa.procurement.order.invoice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the figures of a GST invoice from its text. Invoices differ in layout, so this looks for the labels a tax
 * invoice carries (Rule 46 of the CGST Rules: taxable value, CGST/SGST/IGST, total, GSTINs, invoice number) and takes
 * the amount printed after each, on the same line or, for a table header, in the same column of the next line.
 *
 * <p>When a label appears more than once (an item row and the summary), the largest amount is taken: the summary is
 * never smaller than a row. For the total, the most specific label found wins ("Grand Total" over "Total Amount" over
 * a bare "Total").
 */
public final class InvoiceFigureExtractor {

    private enum Field { GRAND_TOTAL, TAXABLE_VALUE, CGST, SGST, IGST, CESS, TOTAL, IGNORED }

    private record Label(Field field, int priority, Pattern pattern) {
    }

    private record Match(Field field, int priority, int start, int end) {
    }

    private record Candidate(int priority, BigDecimal amount) {
    }

    // Earlier = more specific. Labels that only look like totals (total tax, total quantity) are IGNORED, so a bare
    // "Total" never picks up their amounts.
    private static final List<Label> LABELS = List.of(
            label(Field.GRAND_TOTAL, 0, "grand\\s*total"),
            label(Field.GRAND_TOTAL, 1, "total\\s*invoice\\s*(?:value|amount|amt)"),
            label(Field.GRAND_TOTAL, 2, "invoice\\s*(?:total|value|amount)"),
            label(Field.GRAND_TOTAL, 3, "total\\s*(?:amount|amt|value)\\s*(?:after|incl\\.?|inclusive\\s*of|including)\\s*(?:of\\s*)?(?:taxes|tax|gst)"),
            label(Field.GRAND_TOTAL, 4, "(?:net\\s*|total\\s*)?amount\\s*payable|total\\s*payable|net\\s*payable|balance\\s*due"),
            label(Field.GRAND_TOTAL, 5, "net\\s*(?:amount|total)"),
            label(Field.GRAND_TOTAL, 6, "total\\s*(?:amount|amt)"),
            label(Field.TAXABLE_VALUE, 0, "(?:total\\s*)?taxable\\s*(?:value|amount|amt)|total\\s*taxable|assessable\\s*value"
                    + "|(?:total\\s*)?(?:amount|value)\\s*before\\s*tax|sub\\s*-?\\s*total"),
            label(Field.CGST, 0, "cgst|central\\s*(?:gst|tax)"),
            label(Field.SGST, 0, "sgst|utgst|state\\s*(?:gst|tax)|ut\\s*tax"),
            label(Field.IGST, 0, "igst|integrated\\s*(?:gst|tax)"),
            label(Field.CESS, 0, "(?:gst\\s*)?cess"),
            label(Field.IGNORED, 0, "total\\s*(?:tax|gst)(?:\\s*(?:amount|amt))?|total\\s*(?:qty|quantity|items?|units?|discount|weight)"
                    + "|amount\\s*in\\s*words|rupees"),
            label(Field.TOTAL, 0, "total"));

    private static final Pattern GSTIN = Pattern.compile("(?<![A-Z0-9])[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z](?![A-Z0-9])");
    private static final Pattern CURRENCY = Pattern.compile("₹|(?i)\\bINR\\b|(?i)\\brs\\.?(?=\\s*\\d)");
    private static final Pattern PERCENT = Pattern.compile("\\d+(?:\\.\\d+)?\\s*%");
    private static final Pattern DATE = Pattern.compile("\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b");
    // 1,18,000.00 (Indian grouping), 118,000.00, 118000.00 or 118000; never a piece of a code, date or percentage
    private static final Pattern AMOUNT = Pattern.compile("(?<![\\w.,/])(\\d{1,3}(?:,\\d{2,3})+|\\d+)(\\.\\d{1,2})?(?![\\w,%]|\\.\\d)");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    private static final Pattern INVOICE_NUMBER = Pattern.compile(
            "(?i)(?:invoice|inv|bill)\\s*(?:no|number|num|#)\\.?\\s*[:#.-]?\\s*([A-Z0-9][A-Z0-9/_-]{0,39})");
    private static final int MIN_READABLE_CHARACTERS = 20;

    private InvoiceFigureExtractor() {
    }

    public static InvoiceFigures extract(String text) {
        String safeText = text == null ? "" : text;
        if (safeText.codePoints().filter(Character::isLetterOrDigit).count() < MIN_READABLE_CHARACTERS) {
            return new InvoiceFigures(false, null, null, null, null, null, null, null, List.of());
        }

        Map<Field, List<Candidate>> candidates = new EnumMap<>(Field.class);
        List<String> lines = safeText.lines().map(InvoiceFigureExtractor::clean).filter(line -> !line.isBlank()).toList();
        for (int i = 0; i < lines.size(); i++) {
            String next = i + 1 < lines.size() ? lines.get(i + 1) : null;
            readLine(lines.get(i), next, candidates);
        }

        BigDecimal grandTotal = best(candidates.get(Field.GRAND_TOTAL));
        if (grandTotal == null) {
            grandTotal = largest(candidates.get(Field.TOTAL));
        }
        return new InvoiceFigures(true, invoiceNumber(safeText), grandTotal,
                largest(candidates.get(Field.TAXABLE_VALUE)),
                largest(candidates.get(Field.CGST)),
                largest(candidates.get(Field.SGST)),
                largest(candidates.get(Field.IGST)),
                largest(candidates.get(Field.CESS)),
                gstins(safeText));
    }

    private static void readLine(String line, String nextLine, Map<Field, List<Candidate>> candidates) {
        List<Match> matches = labelsIn(line);
        if (matches.isEmpty()) {
            return;
        }
        boolean anyAmount = false;
        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);
            int segmentEnd = i + 1 < matches.size() ? matches.get(i + 1).start() : line.length();
            List<BigDecimal> amounts = amounts(line.substring(match.end(), segmentEnd));
            if (!amounts.isEmpty()) {
                anyAmount = true;
                add(candidates, match, amounts.getLast());
            }
        }
        // A table header: its amounts are on the next line, one per column
        if (!anyAmount && nextLine != null && !LETTER.matcher(nextLine).find()) {
            List<BigDecimal> below = amounts(nextLine);
            if (matches.size() == 1 && !below.isEmpty()) {
                add(candidates, matches.getFirst(), below.getLast());
            } else if (matches.size() == below.size()) {
                for (int i = 0; i < matches.size(); i++) {
                    add(candidates, matches.get(i), below.get(i));
                }
            }
        }
    }

    // Labels in reading order; where two overlap the longer one wins, and repeats of one field ("CGST Rate", "CGST
    // Amount") count as one column, because rates are removed before reading
    private static List<Match> labelsIn(String line) {
        List<Match> all = new ArrayList<>();
        for (Label label : LABELS) {
            Matcher matcher = label.pattern().matcher(line);
            while (matcher.find()) {
                all.add(new Match(label.field(), label.priority(), matcher.start(), matcher.end()));
            }
        }
        all.sort(Comparator.comparingInt(Match::start).thenComparing(Comparator.comparingInt((Match m) -> m.end() - m.start()).reversed()));
        List<Match> kept = new ArrayList<>();
        int end = 0;
        for (Match match : all) {
            if (match.start() < end) {
                continue;
            }
            if (!kept.isEmpty() && kept.getLast().field() == match.field()
                    && amounts(line.substring(kept.getLast().end(), match.start())).isEmpty()) {
                Match previous = kept.removeLast();
                match = new Match(match.field(), Math.min(previous.priority(), match.priority()), previous.start(), match.end());
            }
            kept.add(match);
            end = match.end();
        }
        return kept;
    }

    private static void add(Map<Field, List<Candidate>> candidates, Match match, BigDecimal amount) {
        if (match.field() != Field.IGNORED) {
            candidates.computeIfAbsent(match.field(), ignored -> new ArrayList<>()).add(new Candidate(match.priority(), amount));
        }
    }

    // Removes what would otherwise read as amounts: currency signs, GSTINs, percentages (tax rates) and dates
    private static String clean(String line) {
        String cleaned = GSTIN.matcher(line).replaceAll(" ");
        cleaned = CURRENCY.matcher(cleaned).replaceAll(" ");
        cleaned = PERCENT.matcher(cleaned).replaceAll(" ");
        return DATE.matcher(cleaned).replaceAll(" ");
    }

    private static List<BigDecimal> amounts(String text) {
        List<BigDecimal> found = new ArrayList<>();
        Matcher matcher = AMOUNT.matcher(text);
        while (matcher.find()) {
            String whole = matcher.group(1).replace(",", "");
            String fraction = matcher.group(2) == null ? "" : matcher.group(2);
            found.add(new BigDecimal(whole + fraction));
        }
        return found;
    }

    private static BigDecimal best(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int top = candidates.stream().mapToInt(Candidate::priority).min().orElseThrow();
        return largest(candidates.stream().filter(candidate -> candidate.priority() == top).toList());
    }

    private static BigDecimal largest(List<Candidate> candidates) {
        return candidates == null ? null : candidates.stream().map(Candidate::amount).max(BigDecimal::compareTo).orElse(null);
    }

    private static String invoiceNumber(String text) {
        Matcher matcher = INVOICE_NUMBER.matcher(text);
        while (matcher.find()) {
            String number = matcher.group(1);
            if (number.chars().anyMatch(Character::isDigit)) {
                return number.toUpperCase();
            }
        }
        return null;
    }

    private static List<String> gstins(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = GSTIN.matcher(text.toUpperCase());
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return List.copyOf(found);
    }

    private static Label label(Field field, int priority, String regex) {
        return new Label(field, priority, Pattern.compile("(?<![a-z])(?:" + regex + ")(?![a-z])", Pattern.CASE_INSENSITIVE));
    }
}
