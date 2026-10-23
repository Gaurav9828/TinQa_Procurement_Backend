package com.tinqa.procurement.payroll;

import com.tinqa.procurement.mail.StaffMailException;
import com.tinqa.procurement.mail.StaffMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Emails the payslips of confirmed salaries, after the confirmation committed (run by {@link PayslipEmailJob}).
 * Each payslip is claimed with a skip-locked row lock in its own transaction, so two instances never send one twice
 * and one failure doesn't hold back the rest. A failed email is tried again a few times, some minutes apart; after
 * that it waits for "Resend payslip". The payslip stays downloadable whatever happens to the email.
 */
@Slf4j
@Service
public class PayslipEmailSender {

    static final int MAX_ATTEMPTS = 5;
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    private static final int BATCH = 25;

    private final SalaryPaymentRepository paymentRepository;
    private final PayslipComposer payslipComposer;
    private final StaffMailer staffMailer;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PayslipEmailSender(SalaryPaymentRepository paymentRepository, PayslipComposer payslipComposer,
                              StaffMailer staffMailer, PlatformTransactionManager transactionManager, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.payslipComposer = payslipComposer;
        this.staffMailer = staffMailer;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Sends the payslips that are due; returns how many went out. */
    public int sendDue() {
        List<Long> due = paymentRepository.findPayslipEmailsDue(MAX_ATTEMPTS, clock.instant().minus(RETRY_AFTER), BATCH);
        int sent = 0;
        for (Long id : due) {
            if (Boolean.TRUE.equals(transaction.execute(status -> sendOne(id)))) {
                sent++;
            }
        }
        return sent;
    }

    private boolean sendOne(Long paymentId) {
        SalaryPayment payment = paymentRepository.claimPayslipEmail(paymentId).orElse(null);
        if (payment == null) {
            // Sent meanwhile, or another instance holds it
            return false;
        }
        Instant now = clock.instant();
        payment.setPayslipEmailAttempts(payment.getPayslipEmailAttempts() + 1);
        payment.setPayslipEmailLastAttemptAt(now);
        payment.setUpdatedAt(now);
        boolean sent = false;
        if (payment.getPayslipEmailTo() == null) {
            payment.setPayslipEmailError("The employee has no email address. Add one in Employee Management, then resend.");
            // Nothing to retry until someone resends
            payment.setPayslipEmailAttempts(MAX_ATTEMPTS);
        } else {
            try {
                PayslipComposer.PayslipFile file = payslipComposer.render(payment);
                staffMailer.sendPayslip(payment.getPayslipEmailTo(), file.employeeName(), file.period(), file.netPay(),
                        file.filename(), file.content());
                payment.setPayslipEmailedAt(now);
                payment.setPayslipEmailError(null);
                sent = true;
            } catch (StaffMailException exception) {
                payment.setPayslipEmailError(exception.isNotConfigured()
                        ? "Email is not set up yet (Company Profile › Email)." : exception.getMessage());
                if (log.isWarnEnabled()) {
                    log.warn("Payslip {} could not be emailed (attempt {}): {}", paymentId, payment.getPayslipEmailAttempts(),
                            exception.getMessage());
                }
            } catch (RuntimeException exception) {
                payment.setPayslipEmailError("The payslip could not be created.");
                log.error("Payslip {} could not be rendered", paymentId, exception);
            }
        }
        paymentRepository.save(payment);
        return sent;
    }
}
