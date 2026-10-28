package com.tinqa.procurement.clientdemo;

/** What a demo cost was for. */
public enum ExpenseKind {
    STAY("Stay"),
    FOOD("Food"),
    TRAVEL("Travel"),
    // Anything else (parking, printing, a gift for the client …); always carries the reason it was needed
    OTHER("Other");

    private final String label;

    ExpenseKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
