package com.tinqa.procurement.security.audit;

import com.tinqa.procurement.security.model.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthAuditService {

    private static final int USER_AGENT_MAX_LENGTH = 512;

    private final AuthAuditEventRepository auditEventRepository;

    // Runs inside the caller's transaction, so the entry is written only if the change commits
    public void record(User user, AuthAuditEventType eventType, HttpServletRequest request, Instant occurredAt) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null && userAgent.length() > USER_AGENT_MAX_LENGTH) {
            userAgent = userAgent.substring(0, USER_AGENT_MAX_LENGTH);
        }

        auditEventRepository.save(AuthAuditEvent.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .eventType(eventType)
                .ipAddress(request.getRemoteAddr())
                .userAgent(userAgent)
                .occurredAt(occurredAt)
                .build());
    }
}
