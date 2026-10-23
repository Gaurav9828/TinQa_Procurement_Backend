package com.tinqa.procurement.payroll;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Emails confirmed payslips every minute (tinqa.payroll.payslip-email.*). */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tinqa.payroll.payslip-email", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PayslipEmailJob {

    private final PayslipEmailSender sender;

    @Scheduled(initialDelayString = "${tinqa.payroll.payslip-email.interval-ms:60000}",
            fixedDelayString = "${tinqa.payroll.payslip-email.interval-ms:60000}")
    public void run() {
        try {
            int sent = sender.sendDue();
            if (sent > 0 && log.isInfoEnabled()) {
                log.info("Emailed {} payslips", sent);
            }
        } catch (RuntimeException exception) {
            // Retried on the next run
            if (log.isWarnEnabled()) {
                log.warn("Payslip emails failed: {}", exception.getMessage());
            }
        }
    }
}
