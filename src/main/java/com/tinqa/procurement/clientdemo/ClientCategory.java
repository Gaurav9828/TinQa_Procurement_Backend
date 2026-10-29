package com.tinqa.procurement.clientdemo;

/** The kind of place the client is; OTHER is described in words on the demo. */
public enum ClientCategory {
    HOTEL("Hotel"),
    HOME_STAY("Home Stay"),
    CORPORATE_OFFICE("Corporate Office"),
    HOSPITAL("Hospital"),
    CLINIC("Clinic"),
    HOME_VISIT("Home Visit"),
    SHOPPING_MALL("Shopping Mall"),
    RESTAURANT("Restaurant"),
    PUBLIC_PLACE("Public Place"),
    OTHER("Other");

    private final String label;

    ClientCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether the client is a business that may have a legal (registered) name and a GSTIN. */
    public boolean hasLegalDetails() {
        return switch (this) {
            case HOTEL, HOME_STAY, CORPORATE_OFFICE, HOSPITAL, CLINIC, RESTAURANT -> true;
            default -> false;
        };
    }

    /** Whether staff can stay there, so its details can fill in a planned stay. */
    public boolean offersStay() {
        return this == HOTEL || this == HOME_STAY;
    }
}
