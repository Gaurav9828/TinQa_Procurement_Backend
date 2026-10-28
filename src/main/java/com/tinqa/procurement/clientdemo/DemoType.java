package com.tinqa.procurement.clientdemo;

/** Why staff visit the client: a product demo they booked, or promoting the products at the client's place. */
public enum DemoType {
    APPOINTMENT("Appointment"),
    PROMOTION("Promotion");

    private final String label;

    DemoType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
