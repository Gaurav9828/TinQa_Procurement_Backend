package com.tinqa.procurement.barcode.entity;

import com.tinqa.procurement.barcode.enums.BarcodeEventType;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/** Something that happened to a barcode; never changed or deleted. */
@Entity
@Table(name = "barcode_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BarcodeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "barcode_id", nullable = false, updatable = false)
    private Barcode barcode;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30, updatable = false)
    private BarcodeEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30, updatable = false)
    private BarcodeStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30, updatable = false)
    private BarcodeStatus toStatus;

    @Column(name = "from_fitted_into_id", updatable = false)
    private Long fromFittedIntoId;

    @Column(name = "to_fitted_into_id", updatable = false)
    private Long toFittedIntoId;

    @Column(name = "old_code", length = 32, updatable = false)
    private String oldCode;

    @Column(name = "new_code", length = 32, updatable = false)
    private String newCode;

    @Column(name = "movement_id", updatable = false)
    private UUID movementId;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_name", length = 255, updatable = false)
    private String actorName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
