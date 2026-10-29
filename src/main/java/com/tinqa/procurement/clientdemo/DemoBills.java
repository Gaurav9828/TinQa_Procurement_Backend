package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.validation.OptionalText;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What a bill must say for its kind, and how it is described on the demo's timeline. Shared by single bills
 * ({@link DemoBillService}) and the day-by-day plan sent with the demo ({@link ClientDemoService}).
 */
final class DemoBills {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private DemoBills() {
    }

    /** Validates the request for its kind and copies it onto the bill; fields that don't belong to the kind are cleared. */
    static void apply(ClientDemo demo, DemoExpense expense, ClientDemoDTOs.ExpenseRequest request) {
        ExpenseKind kind = request.getKind();
        Integer day = request.getDayNumber();
        String vendor = OptionalText.blankToNull(request.getVendorName());
        if (kind != ExpenseKind.OTHER && day == null) {
            throw ApiErrors.fieldError("dayNumber", "Choose the day of the visit this bill belongs to.");
        }
        if (day != null && day > demo.getNumberOfDays()) {
            throw ApiErrors.fieldError("dayNumber", "The visit is " + demo.getNumberOfDays() + (demo.getNumberOfDays() == 1 ? " day" : " days")
                    + " long, so there is no day " + day + ".");
        }
        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal gst = request.getGstAmount() == null ? null : request.getGstAmount().setScale(2, RoundingMode.HALF_UP);
        if (gst != null && gst.compareTo(amount) > 0) {
            throw ApiErrors.fieldError("gstAmount", "The GST is part of the amount, so it can't be more than the amount.");
        }
        switch (kind) {
            case STAY -> {
                require(vendor, "vendorName", "Enter the hotel's name.");
                checkTimes(demo, request, "check-in", "check-out");
            }
            case FOOD -> {
                require(vendor, "vendorName", "Enter the restaurant's name.");
                if (request.getMealType() == null) {
                    throw ApiErrors.fieldError("mealType", "Choose the meal: breakfast, lunch, snacks or dinner.");
                }
            }
            case TRAVEL -> {
                require(OptionalText.blankToNull(request.getFromPlace()), "fromPlace", "Enter where the journey started.");
                require(OptionalText.blankToNull(request.getToPlace()), "toPlace", "Enter where the journey ended.");
                if (request.getTravelMode() == null) {
                    throw ApiErrors.fieldError("travelMode", "Choose how the staff travelled.");
                }
                checkTimes(demo, request, "departure", "arrival");
            }
            case OTHER -> {
                require(OptionalText.blankToNull(request.getDescription()), "description", "Say what this cost was for.");
                require(OptionalText.blankToNull(request.getReason()), "reason", "Give the reason this cost was needed.");
            }
        }

        boolean stay = kind == ExpenseKind.STAY;
        boolean timed = (stay || kind == ExpenseKind.TRAVEL) && request.getStartTime() != null;
        expense.setKind(kind);
        expense.setDayNumber(day);
        expense.setVendorName(vendor);
        expense.setVendorAddress(stay || kind == ExpenseKind.FOOD ? OptionalText.blankToNull(request.getVendorAddress()) : null);
        expense.setVendorMapLink(stay ? OptionalText.blankToNull(request.getVendorMapLink()) : null);
        expense.setVendorPhone(stay ? OptionalText.blankToNull(request.getVendorPhone()) : null);
        expense.setVendorGstin(kind == ExpenseKind.TRAVEL ? null : OptionalText.blankToNull(request.getVendorGstin()));
        expense.setMealType(kind == ExpenseKind.FOOD ? request.getMealType() : null);
        expense.setFromPlace(kind == ExpenseKind.TRAVEL ? OptionalText.blankToNull(request.getFromPlace()) : null);
        expense.setToPlace(kind == ExpenseKind.TRAVEL ? OptionalText.blankToNull(request.getToPlace()) : null);
        expense.setTravelMode(kind == ExpenseKind.TRAVEL ? request.getTravelMode() : null);
        expense.setStartDate(timed ? request.getStartDate() : null);
        expense.setStartTime(timed ? minutes(request.getStartTime()) : null);
        expense.setEndDate(timed ? request.getEndDate() : null);
        expense.setEndTime(timed ? minutes(request.getEndTime()) : null);
        expense.setReferenceNumber(OptionalText.blankToNull(request.getReferenceNumber()));
        expense.setAmount(amount);
        expense.setGstAmount(gst);
        expense.setDescription(OptionalText.blankToNull(request.getDescription()));
        expense.setReason(kind == ExpenseKind.OTHER ? OptionalText.blankToNull(request.getReason()) : null);
    }

    static String describe(DemoExpense expense) {
        String day = expense.getDayNumber() == null ? "" : "Day " + expense.getDayNumber() + " ";
        String what = switch (expense.getKind()) {
            case STAY -> "stay at " + expense.getVendorName() + span(expense);
            case FOOD -> expense.getMealType().name().toLowerCase(Locale.ROOT) + " at " + expense.getVendorName();
            case TRAVEL -> expense.getTravelMode().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " "
                    + expense.getFromPlace() + " to " + expense.getToPlace() + span(expense);
            case OTHER -> expense.getDescription();
        };
        return day + what + " " + ClientDemoService.rupees(expense.getAmount());
    }

    /**
     * A stay's check-in and check-out, or a journey's departure and arrival: each a date and a time, all four or none.
     * It starts on its day of the trip or the day after (past midnight), ends after it starts, and ends by the day
     * after the trip's last day (a last night's check-out, an overnight journey home).
     */
    private static void checkTimes(ClientDemo demo, ClientDemoDTOs.ExpenseRequest request, String start, String end) {
        long given = java.util.stream.Stream.of(request.getStartDate(), request.getStartTime(), request.getEndDate(), request.getEndTime())
                .filter(java.util.Objects::nonNull).count();
        if (given == 0) {
            return;
        }
        if (given < 4) {
            throw ApiErrors.fieldError("endTime", "Give the " + start + " and " + end + " dates and times, or none of them.");
        }
        LocalDateTime from = LocalDateTime.of(request.getStartDate(), minutes(request.getStartTime()));
        LocalDateTime to = LocalDateTime.of(request.getEndDate(), minutes(request.getEndTime()));
        if (!to.isAfter(from)) {
            throw ApiErrors.fieldError("endTime", "The " + end + " must be after the " + start + ".");
        }
        if (request.getDayNumber() != null) {
            LocalDate day = demo.getStartDate().plusDays(request.getDayNumber() - 1L);
            if (from.toLocalDate().isBefore(day) || from.toLocalDate().isAfter(day.plusDays(1))) {
                throw ApiErrors.fieldError("startDate", "Day " + request.getDayNumber() + " is " + DATE.format(day) + ", so the " + start
                        + " is on " + DATE.format(day) + " or " + DATE.format(day.plusDays(1)) + ".");
            }
        }
        LocalDate latest = demo.endDate().plusDays(1);
        if (to.toLocalDate().isAfter(latest)) {
            throw ApiErrors.fieldError("endDate", "The trip ends on " + DATE.format(demo.endDate()) + ", so the " + end + " is by "
                    + DATE.format(latest) + " at the latest.");
        }
    }

    // "30 Oct 18:00 → 31 Oct 06:00" for a dated stay or journey
    private static String span(DemoExpense expense) {
        if (expense.getStartDate() == null) {
            return "";
        }
        return " " + DATE.format(expense.getStartDate()) + " " + expense.getStartTime() + " → " + DATE.format(expense.getEndDate())
                + " " + expense.getEndTime();
    }

    private static LocalTime minutes(LocalTime time) {
        return time.withSecond(0).withNano(0);
    }

    private static void require(String value, String field, String message) {
        if (value == null) {
            throw ApiErrors.fieldError(field, message);
        }
    }
}
