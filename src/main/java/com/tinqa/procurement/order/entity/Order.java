package com.tinqa.procurement.order.entity;

import com.tinqa.procurement.supplier.entity.Supplier;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.payment.PaymentMethod;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, length = 50)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "order_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal orderQuantity;

    @Column(name = "unit_type", nullable = false, length = 20)
    private String unitType;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "total_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "shipment_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal shipmentPrice;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_breakup", columnDefinition = "jsonb")
    private Map<String, Object> taxBreakup;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 30)
    @Builder.Default
    private OrderStatus orderStatus = OrderStatus.QUOTATION;

    // Payment plan: the advance and midterm percentages; the final part (the rest) is paid after testing. Null on
    // orders made before plans existed
    @Column(name = "advance_percent", precision = 5, scale = 2)
    private BigDecimal advancePercent;

    @Column(name = "midterm_percent", precision = 5, scale = 2)
    private BigDecimal midtermPercent;

    // How the supplier expects to be paid
    @Enumerated(EnumType.STRING)
    @Column(name = "expected_payment_method", length = 20)
    private PaymentMethod expectedPaymentMethod;

    @Column(name = "expected_delivery")
    private LocalDate expectedDelivery;

    @Column(name = "actual_delivery")
    private LocalDate actualDelivery;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "additional_info", columnDefinition = "jsonb")
    private Map<String, Object> additionalInfo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    // Unit price x quantity, plus shipment
    public void recalculateTotalPrice() {
        totalPrice = unitPrice.multiply(orderQuantity).add(shipmentPrice);
    }
}
