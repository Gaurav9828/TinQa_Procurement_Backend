package com.tinqa.procurement.meeting;

public enum MeetingType {
    CLIENT("Client meeting"),
    BUSINESS_TO_BUSINESS("Business to business"),
    BUSINESS_TO_DEALER("Business to dealer"),
    CLIENT_TO_CLIENT("Client to client"),
    INTERNAL("Internal"),
    OTHER("Other");

    private final String label;

    MeetingType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
