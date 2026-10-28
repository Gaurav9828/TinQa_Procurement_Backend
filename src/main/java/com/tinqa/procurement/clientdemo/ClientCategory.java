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
}
