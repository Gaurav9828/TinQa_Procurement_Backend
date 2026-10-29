package com.tinqa.procurement.clientdemo;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
import java.util.regex.Pattern;

/**
 * One client visited on a demo trip, on one day between two times; visits on the same day never overlap, and the same
 * client (by its own phone or email) is never visited twice in a row on one day. A business
 * client ({@link ClientCategory#hasLegalDetails()}) may carry its legal name and GSTIN.
 */
@Entity
@Table(name = "client_demo_visit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoVisit {

    private static final Pattern INDIAN_PHONE = Pattern.compile("^(?:\\+91|0)?[6-9]\\d{9}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demo_id", nullable = false, updatable = false)
    private ClientDemo demo;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_category", nullable = false, length = 30)
    private ClientCategory clientCategory;

    @Column(name = "client_category_other", length = 100)
    private String clientCategoryOther;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "legal_name", length = 150)
    private String legalName;

    @Column(length = 15)
    private String gstin;

    // The client's own phone and email (a hotel's front desk, an office's reception), which tell clients apart
    @Column(name = "client_phone", nullable = false, length = 20)
    private String clientPhone;

    @Column(name = "client_email")
    private String clientEmail;

    @Column(name = "contact_person", length = 100)
    private String contactPerson;

    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "address_line", nullable = false, length = 300)
    private String addressLine;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 10)
    private String pincode;

    @Column(name = "map_link", length = 500)
    private String mapLink;

    // Day of the trip, 1 = the demo's start date
    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** Whether it is the same client as the other visit: the same phone, or the same email when both have one. */
    public boolean sameClientAs(DemoVisit other) {
        return phoneKey(clientPhone).equals(phoneKey(other.clientPhone))
                || clientEmail != null && other.clientEmail != null && clientEmail.equalsIgnoreCase(other.clientEmail);
    }

    // An Indian number however it is written (+91…, 0… or the 10 digits alone) is its 10 digits; others are as typed
    static String phoneKey(String phone) {
        String trimmed = phone.trim();
        return INDIAN_PHONE.matcher(trimmed).matches() ? trimmed.substring(trimmed.length() - 10) : trimmed;
    }

    /** "Day 1 10:00–13:00 Hotel Sunrise", as the demo's timeline shows it. */
    public String describe() {
        return "Day " + dayNumber + " " + startTime + "–" + endTime + " " + clientName;
    }
}
