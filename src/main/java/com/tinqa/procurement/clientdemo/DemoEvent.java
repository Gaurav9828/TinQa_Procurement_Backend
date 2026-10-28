package com.tinqa.procurement.clientdemo;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One entry of a demo's timeline, with the reason given for it. Never changed. */
@Entity
@Table(name = "client_demo_event")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "demo_id", nullable = false, updatable = false)
    private Long demoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private DemoEventType type;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Column(length = 1000, updatable = false)
    private String detail;

    @Column(length = 500, updatable = false)
    private String reason;
}
