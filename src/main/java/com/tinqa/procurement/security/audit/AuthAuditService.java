package com.tinqa.procurement.security.audit;

import com.tinqa.procurement.security.model.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthAuditService {

    private static final int USER_AGENT_MAX_LENGTH = 512;
    private static final int USERNAME_MAX_LENGTH = 100;

    private final AuthAuditEventRepository auditEventRepository;
    private final Clock clock;

    /**
     * A sign-in attempt. Written in its own transaction, so a failed attempt (whose request ends in an error) is kept.
     * {@code userId} is null when the username matches no account; the typed username is kept as entered.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLogin(Long userId, String username, AuthAuditEventType eventType) {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
        String name = username == null ? "" : username.strip();
        auditEventRepository.save(AuthAuditEvent.builder()
                .userId(userId)
                .username(name.length() > USERNAME_MAX_LENGTH ? name.substring(0, USERNAME_MAX_LENGTH) : name)
                .eventType(eventType)
                .ipAddress(request == null ? null : request.getRemoteAddr())
                .userAgent(request == null ? null : userAgent(request))
                .occurredAt(Instant.now(clock))
                .build());
    }

    private static String userAgent(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return userAgent != null && userAgent.length() > USER_AGENT_MAX_LENGTH ? userAgent.substring(0, USER_AGENT_MAX_LENGTH) : userAgent;
    }

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
