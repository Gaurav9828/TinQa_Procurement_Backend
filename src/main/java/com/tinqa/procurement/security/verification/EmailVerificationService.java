package com.tinqa.procurement.security.verification;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.util.SecureTokens;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.mail.StaffMailErrors;
import com.tinqa.procurement.mail.StaffMailException;
import com.tinqa.procurement.mail.StaffMailer;
import com.tinqa.procurement.security.config.AccountEmailProperties;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.verification.dto.EmailVerificationSentResponse;
import com.tinqa.procurement.security.verification.dto.EmailVerifiedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Proves that a new employee owns their personal email before the account is created. A 5-digit code is emailed
 * (kept only as a hash); the right code returns a verification token, and the employee creation consumes it once.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    public static final int CODE_LENGTH = 5;

    private final EmailVerificationRepository repository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StaffMailer mailer;
    private final PasswordEncoder passwordEncoder;
    private final AccountEmailProperties properties;
    private final Clock clock;

    /**
     * Emails a code to an address no account or employee uses yet. Codes to one address are spaced out and capped per
     * hour; a failed send leaves nothing behind.
     *
     * @param name        how the email greets them (may be blank)
     * @param requestedBy the administrator's user id
     */
    @Transactional
    public EmailVerificationSentResponse requestCode(String email, String name, Long requestedBy) {
        String address = normalize(email);
        if (userRepository.existsByEmailIgnoreCase(address)
                || employeeRepository.findFirstByPersonalEmailIgnoreCaseAndIdNot(address, -1L).isPresent()) {
            throw ApiErrors.fieldError("email", "Email '" + address + "' is already used by another account or employee.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AccountEmailProperties.Verification rules = properties.verification();
        Optional<EmailVerification> latest = repository.findFirstByEmailOrderByCreatedAtDescIdDesc(address);
        if (latest.isPresent() && latest.get().getCreatedAt().isAfter(now.minus(rules.resendCooldown()))) {
            long wait = Math.max(1, Duration.between(now, latest.get().getCreatedAt().plus(rules.resendCooldown())).toSeconds());
            throw ApiErrors.of(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.VERIFICATION_TOO_SOON,
                    "A code was just sent to this address. You can ask for a new one in " + wait + " seconds.");
        }
        if (repository.countByEmailAndCreatedAtAfter(address, now.minusHours(1)) >= rules.maxCodesPerHour()) {
            throw ApiErrors.of(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.VERIFICATION_TOO_SOON,
                    "Too many codes were sent to this address. Please try again in an hour.");
        }

        String code = SecureTokens.digits(CODE_LENGTH);
        repository.save(EmailVerification.builder()
                .email(address)
                .codeHash(passwordEncoder.encode(code))
                .codeExpiresAt(now.plus(rules.codeValidity()))
                .requestedBy(requestedBy)
                .createdAt(now)
                .build());
        try {
            mailer.sendVerificationCode(address, name == null || name.isBlank() ? null : name.trim(), code,
                    rules.codeValidity().toMinutes());
        } catch (StaffMailException exception) {
            throw StaffMailErrors.toApiException(exception);
        }
        return new EmailVerificationSentResponse(address, rules.codeValidity().toMinutes(), rules.resendCooldown().toSeconds());
    }

    /** Checks the code; a wrong one counts against the attempts (kept even though the call fails). */
    @Transactional(noRollbackFor = ApiException.class)
    public EmailVerifiedResponse confirm(String email, String code) {
        LocalDateTime now = LocalDateTime.now(clock);
        AccountEmailProperties.Verification rules = properties.verification();
        EmailVerification verification = repository.findFirstByEmailOrderByCreatedAtDescIdDesc(normalize(email))
                .filter(found -> found.getVerifiedAt() == null)
                .orElseThrow(() -> ApiErrors.of(HttpStatus.BAD_REQUEST, ErrorCodes.VERIFICATION_CODE_INVALID,
                        "There is no code waiting for this address. Ask for a new one."));
        if (verification.getCodeExpiresAt().isBefore(now)) {
            throw ApiErrors.of(HttpStatus.BAD_REQUEST, ErrorCodes.VERIFICATION_CODE_EXPIRED, "This code has expired. Ask for a new one.");
        }
        if (verification.getAttempts() >= rules.maxAttempts()) {
            throw ApiErrors.of(HttpStatus.BAD_REQUEST, ErrorCodes.VERIFICATION_CODE_LOCKED, "Too many wrong codes. Ask for a new one.");
        }
        if (!passwordEncoder.matches(code.trim(), verification.getCodeHash())) {
            verification.setAttempts(verification.getAttempts() + 1);
            repository.save(verification);
            int left = rules.maxAttempts() - verification.getAttempts();
            throw ApiErrors.of(HttpStatus.BAD_REQUEST, left > 0 ? ErrorCodes.VERIFICATION_CODE_INVALID : ErrorCodes.VERIFICATION_CODE_LOCKED,
                    left > 0 ? "That code isn't right. " + left + (left == 1 ? " try" : " tries") + " left."
                            : "That code isn't right. Ask for a new one.");
        }

        String token = SecureTokens.urlToken();
        verification.setVerifiedAt(now);
        verification.setTokenHash(SecureTokens.sha256Hex(token));
        verification.setTokenExpiresAt(now.plus(rules.tokenValidity()));
        repository.save(verification);
        return new EmailVerifiedResponse(verification.getEmail(), token, verification.getTokenExpiresAt());
    }

    /**
     * Uses up the verification of {@code email} in the account creation's transaction.
     *
     * @param field the request field the token came in, for the error
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(String email, String token, String field) {
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<EmailVerification> verification = token == null || token.isBlank() ? Optional.empty()
                : repository.findByTokenHash(SecureTokens.sha256Hex(token.trim()))
                .filter(found -> found.getEmail().equals(normalize(email)))
                .filter(found -> found.getConsumedAt() == null && found.getTokenExpiresAt().isAfter(now));
        if (verification.isEmpty()) {
            String message = "Verify the email address with the code sent to it first (a verification is valid for "
                    + properties.verification().tokenValidity().toMinutes() + " minutes).";
            throw new ApiException(message, ErrorCodes.EMAIL_NOT_VERIFIED, HttpStatus.BAD_REQUEST, List.of(ApiErrors.error(field, message)));
        }
        verification.get().setConsumedAt(now);
        repository.save(verification.get());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
