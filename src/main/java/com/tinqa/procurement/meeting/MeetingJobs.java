package com.tinqa.procurement.meeting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: due 24-hour reminders, then the meeting emails waiting (tinqa.meetings.jobs.*). */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tinqa.meetings.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MeetingJobs {

    private final MeetingMailer mailer;

    @Scheduled(initialDelayString = "${tinqa.meetings.jobs.interval-ms:60000}",
            fixedDelayString = "${tinqa.meetings.jobs.interval-ms:60000}")
    public void run() {
        try {
            int reminded = mailer.sendReminders();
            int emailed = mailer.sendEmails();
            if ((reminded > 0 || emailed > 0) && log.isInfoEnabled()) {
                log.info("Meetings: {} reminders, {} emails sent", reminded, emailed);
            }
        } catch (RuntimeException exception) {
            // Retried on the next run
            if (log.isWarnEnabled()) {
                log.warn("Meeting reminders or emails failed: {}", exception.getMessage());
            }
        }
    }
}
