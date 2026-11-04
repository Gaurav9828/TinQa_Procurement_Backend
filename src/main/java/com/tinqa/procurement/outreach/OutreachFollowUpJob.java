package com.tinqa.procurement.outreach;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends the 09:00 follow-up reminders of client outreach every few minutes (tinqa.outreach.follow-up-reminder.*). */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tinqa.outreach.follow-up-reminder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutreachFollowUpJob {

    private final OutreachFollowUpReminder reminder;

    @Scheduled(initialDelayString = "${tinqa.outreach.follow-up-reminder.interval-ms:300000}",
            fixedDelayString = "${tinqa.outreach.follow-up-reminder.interval-ms:300000}")
    public void run() {
        try {
            int sent = reminder.remindDue();
            if (sent > 0 && log.isInfoEnabled()) {
                log.info("Sent {} outreach follow-up reminders", sent);
            }
        } catch (RuntimeException exception) {
            // Retried on the next run
            if (log.isWarnEnabled()) {
                log.warn("Outreach follow-up reminders failed: {}", exception.getMessage());
            }
        }
    }
}
