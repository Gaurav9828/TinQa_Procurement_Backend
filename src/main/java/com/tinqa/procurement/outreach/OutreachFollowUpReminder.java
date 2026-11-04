package com.tinqa.procurement.outreach;

import com.tinqa.procurement.common.util.BusinessTime;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * At 09:00 (company time) on a follow-up's day, the person who logged the contact gets an in-app reminder, once
 * (run by {@link OutreachFollowUpJob}). Follow-ups superseded by a later contact with the business are marked done
 * when that contact is logged, so they never remind. Due reminders are claimed with a skip-locked row lock, so two
 * instances never send one twice; when nothing is due this is one indexed query.
 */
@Service
public class OutreachFollowUpReminder {

    static final LocalTime REMINDER_TIME = LocalTime.of(9, 0);
    private static final int BATCH = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    private final OutreachContactRepository contactRepository;
    private final OutreachBusinessRepository businessRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OutreachFollowUpReminder(OutreachContactRepository contactRepository, OutreachBusinessRepository businessRepository,
                                    NotificationService notificationService, PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.contactRepository = contactRepository;
        this.businessRepository = businessRepository;
        this.notificationService = notificationService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Sends the reminders that are due; returns how many went out. */
    public int remindDue() {
        Integer sent = transaction.execute(status -> remindBatch(cutoff()));
        return sent == null ? 0 : sent;
    }

    // Follow-ups before this instant are due: until 09:00 the ones of earlier days, from 09:00 today's too
    Instant cutoff() {
        ZonedDateTime now = clock.instant().atZone(BusinessTime.ZONE);
        return (now.toLocalTime().isBefore(REMINDER_TIME) ? now.toLocalDate() : now.toLocalDate().plusDays(1))
                .atStartOfDay(BusinessTime.ZONE).toInstant();
    }

    private int remindBatch(Instant cutoff) {
        List<Object[]> due = contactRepository.lockRemindersDue(cutoff, BATCH);
        if (due.isEmpty()) {
            return 0;
        }
        Set<Long> businessIds = due.stream().map(row -> ((Number) row[1]).longValue()).collect(Collectors.toSet());
        Map<Long, String> businessNames = businessRepository.findAllById(businessIds).stream()
                .collect(Collectors.toMap(OutreachBusiness::getId, OutreachBusiness::getName));
        List<NotificationService.Message> messages = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Object[] row : due) {
            long contactId = ((Number) row[0]).longValue();
            long businessId = ((Number) row[1]).longValue();
            Instant followUpAt = instant(row[3]);
            String action = switch (NextAction.valueOf((String) row[4])) {
                case DEMO_REQUESTED -> "Demo requested by ";
                case MEETING_REQUESTED -> "Meeting requested by ";
                default -> "Call back ";
            };
            ids.add(contactId);
            messages.add(new NotificationService.Message(((Number) row[2]).longValue(), "Follow-up due today",
                    action + businessNames.getOrDefault(businessId, "a business") + " – follow up "
                            + TIME.format(followUpAt.atZone(BusinessTime.ZONE)) + ".",
                    NotificationLinks.OUTREACH_BUSINESSES + "/" + businessId));
        }
        notificationService.createEach(messages);
        contactRepository.markReminded(ids, clock.instant());
        return ids.size();
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalStateException("Unexpected time " + value);
        };
    }
}
