package com.tinqa.procurement.order.invoice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderInvoiceDTOs {

    private OrderInvoiceDTOs() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long orderId;
        private String fileName;
        /** As read from the invoice, when it could be */
        private String invoiceNumber;
        private InvoiceVerificationStatus status;
        private BigDecimal expectedTotal;
        private BigDecimal foundTotal;
        private List<InvoiceCheck> checks;
        private String uploadedByUsername;
        private Instant uploadedAt;
        /** An approver accepted it although it didn't match */
        private String overrideByUsername;
        private Instant overrideAt;
        private String overrideReason;
        /** The order can be marked delivered without an approver's override */
        private boolean matched;
    }
}
