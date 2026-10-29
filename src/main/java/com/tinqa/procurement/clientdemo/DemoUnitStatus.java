package com.tinqa.procurement.clientdemo;

/**
 * Where a product unit picked for a demo is: reserved for the demo's dates (still in stock, no overlapping demo can
 * pick it), handed out on the day (out of stock), handed back (in stock again), written off by a Super Admin when it
 * did not come back, or never taken (the demo ended or was cancelled while it was only reserved).
 */
public enum DemoUnitStatus {
    RESERVED("Reserved"),
    OUT("Out on demo"),
    RETURNED("Returned"),
    WRITTEN_OFF("Written off"),
    NOT_TAKEN("Not taken");

    private final String label;

    DemoUnitStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether the unit was handed out at some point, so it stays on the demo's record. */
    public boolean handedOut() {
        return this == OUT || this == RETURNED || this == WRITTEN_OFF;
    }
}
