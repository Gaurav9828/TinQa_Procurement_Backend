package com.tinqa.procurement.common.monitoring;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The SQL statements run on the current request's thread between {@link #begin()} and {@link #end()}. Statements
 * outside a request (scheduled jobs, startup) are not counted.
 */
final class RequestDbStats {

    private static final ThreadLocal<RequestDbStats> CURRENT = new ThreadLocal<>();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b\\d+\\b");
    private static final Pattern IN_LIST = Pattern.compile("\\(\\?(?:, ?\\?)+\\)");
    private static final int MAX_SHAPE_LENGTH = 300;

    /** What one request did: statements run, time spent in the database, and its most repeated statement. */
    record Snapshot(int statements, long dbMillis, int maxRepeat, String mostRepeated) {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0, "");
    }

    private int statements;
    private long dbMillis;
    private final Map<String, Integer> byShape = new HashMap<>();

    private RequestDbStats() {
    }

    static void begin() {
        CURRENT.set(new RequestDbStats());
    }

    static Snapshot end() {
        RequestDbStats stats = CURRENT.get();
        CURRENT.remove();
        return stats == null ? Snapshot.EMPTY : stats.snapshot();
    }

    static void record(String sql, long elapsedMillis) {
        RequestDbStats stats = CURRENT.get();
        if (stats == null) {
            return;
        }
        stats.statements++;
        stats.dbMillis += elapsedMillis;
        stats.byShape.merge(shape(sql), 1, Integer::sum);
    }

    /** The statement with its literals and IN-list lengths taken out, so the same query for different rows matches. */
    static String shape(String sql) {
        String s = WHITESPACE.matcher(sql == null ? "" : sql.trim()).replaceAll(" ");
        s = STRING_LITERAL.matcher(s).replaceAll("?");
        s = NUMBER_LITERAL.matcher(s).replaceAll("?");
        s = IN_LIST.matcher(s).replaceAll("(?)");
        // Long column lists: keep the start (which table) and the end (the WHERE clause that tells the queries apart)
        int half = MAX_SHAPE_LENGTH / 2;
        return s.length() > MAX_SHAPE_LENGTH ? s.substring(0, half) + " … " + s.substring(s.length() - half) : s;
    }

    private Snapshot snapshot() {
        Map.Entry<String, Integer> top = byShape.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        return top == null ? new Snapshot(statements, dbMillis, 0, "")
                : new Snapshot(statements, dbMillis, top.getValue(), top.getKey());
    }
}
