package com.tinqa.procurement.approval.summary;

import com.tinqa.procurement.security.access.CurrentAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How many requests wait in each Approvals Hub queue the caller may decide, in one SQL statement (the
 * approval_queue_counts function, V14__approval_queue_counts.sql, last replaced in V16) instead of one list download per queue.
 */
@Service
@RequiredArgsConstructor
public class ApprovalSummaryService {

    private static final String COUNTS = "SELECT queue, waiting FROM approval_queue_counts(string_to_array(:queues, ','))";

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentAccess currentAccess;

    /** Queue name to waiting count, in {@link ApprovalQueue} order; queues the caller can't decide are left out. */
    @Transactional(readOnly = true)
    public Map<String, Long> summary() {
        List<ApprovalQueue> allowed = Arrays.stream(ApprovalQueue.values())
                .filter(queue -> queue.required().stream().allMatch(currentAccess::has))
                .toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        if (allowed.isEmpty()) {
            return counts;
        }
        allowed.forEach(queue -> counts.put(queue.name(), 0L));
        String names = String.join(",", counts.keySet());
        jdbc.query(COUNTS, new MapSqlParameterSource("queues", names), row -> {
            String queue = row.getString("queue");
            if (counts.containsKey(queue)) {
                counts.put(queue, row.getLong("waiting"));
            }
        });
        return counts;
    }
}
