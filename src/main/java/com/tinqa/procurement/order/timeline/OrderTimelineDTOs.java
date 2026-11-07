package com.tinqa.procurement.order.timeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

public final class OrderTimelineDTOs {

    private OrderTimelineDTOs() {
    }

    public enum Kind {
        STATUS,
        CHANGE_REQUEST,
        PAYMENT,
        INVOICE,
        REFUND_CLAIM,
        // A return of failed pieces to the supplier: a branch "return-{id}"
        RETURN
    }

    public enum Tone {
        DONE,
        WAITING,
        REJECTED,
        INFO
    }

    /** An order's flow history, oldest first: its main line (status changes, invoices, refund claim) and its branches. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Timeline {
        private Long orderId;
        private String orderNumber;
        private List<Event> events;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        /** "status-12", "change-4-requested", "payment-7-3", "invoice-2", "claim-1-opened" */
        private String id;
        private Instant at;
        private Kind kind;
        /** "Supplier Manager approved the quotation" */
        private String title;
        private String detail;
        /** The designation, "Supplier", or null for the system */
        private String actorRole;
        /** Staff view only: always null for the supplier */
        private String actorName;
        private Tone tone;
        /** Null on the order's main line; "payment-7" or "change-4" for a branch */
        private String branchId;
        /** "Advance payment ₹30000.00 (bank transfer)" */
        private String branchTitle;
    }
}
