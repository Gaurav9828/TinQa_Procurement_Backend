package com.tinqa.procurement.clientdemo;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One product unit, by its barcode, taken to a demo for accountability: picked when the demo is planned, handed out on
 * the demo's days (it leaves stock, here and on the storefront) and handed back afterwards. See {@link DemoUnitStatus}.
 */
@Entity
@Table(name = "client_demo_unit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demo_id", nullable = false, updatable = false)
    private ClientDemo demo;

    @Column(name = "barcode_id", nullable = false, updatable = false)
    private Long barcodeId;

    // The unit's code when it was picked
    @Column(nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DemoUnitStatus status;

    @Column(name = "handed_out_at")
    private Instant handedOutAt;

    @Column(name = "handed_out_by")
    private Long handedOutBy;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "returned_by")
    private Long returnedBy;

    // The storefront stock moves of the hand-out and the return (see DemoStockClient)
    @Column(name = "out_move_key", length = 80)
    private String outMoveKey;

    @Column(name = "back_move_key", length = 80)
    private String backMoveKey;

    @Column(name = "write_off_reason", length = 500)
    private String writeOffReason;
}
