package com.tinqa.procurement.security.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

// Never add a password or password hash field here
@Entity
@Table(name = "auth_audit_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Column(nullable = false, length = 100)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuthAuditEventType eventType;

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(nullable = false)
    private Instant occurredAt;
}
