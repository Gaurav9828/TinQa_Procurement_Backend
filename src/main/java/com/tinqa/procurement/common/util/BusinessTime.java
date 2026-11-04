package com.tinqa.procurement.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** The company's time zone (India), for days, reminders at a time of day, and times shown with their offset. */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private BusinessTime() {
    }

    /** The instant with the company's offset, e.g. 2026-11-04T15:30+05:30; null stays null. */
    public static OffsetDateTime of(Instant instant) {
        return instant == null ? null : instant.atZone(ZONE).toOffsetDateTime();
    }

    public static Instant toInstant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    public static Instant startOf(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    public static LocalDate today(java.time.Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
