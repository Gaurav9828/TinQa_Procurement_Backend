package com.tinqa.procurement.security.verification;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.util.SecureTokens;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.mail.StaffMailer;
import com.tinqa.procurement.security.config.AccountEmailProperties;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.verification.dto.EmailVerifiedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final String EMAIL = "priya@gmail.com";

    private final EmailVerificationRepository repository = mock(EmailVerificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final StaffMailer mailer = mock(StaffMailer.class);
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        AccountEmailProperties properties = new AccountEmailProperties(
                new AccountEmailProperties.Verification(Duration.ofMinutes(10), 5, Duration.ofSeconds(60), 5, Duration.ofMinutes(30)),
                new AccountEmailProperties.PasswordLink(Duration.ofMinutes(15), Duration.ofSeconds(60)));
        service = new EmailVerificationService(repository, users, employees, mailer, new BCryptPasswordEncoder(4), properties, CLOCK);
        when(employees.findFirstByPersonalEmailIgnoreCaseAndIdNot(anyString(), anyLong())).thenReturn(Optional.empty());
        when(repository.findFirstByEmailOrderByCreatedAtDescIdDesc(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void theCodeIsEmailedAndOnlyItsHashIsKeptThenOneCreationUsesTheVerification() {
        service.requestCode(" Priya@Gmail.com ", "Priya", 1L);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendVerificationCode(eq(EMAIL), eq("Priya"), code.capture(), eq(10L));
        ArgumentCaptor<EmailVerification> saved = ArgumentCaptor.forClass(EmailVerification.class);
        verify(repository).save(saved.capture());
        EmailVerification verification = saved.getValue();
        assertThat(verification.getCodeHash()).isNotEqualTo(code.getValue());

        when(repository.findFirstByEmailOrderByCreatedAtDescIdDesc(EMAIL)).thenReturn(Optional.of(verification));
        assertThatThrownBy(() -> service.confirm(EMAIL, code.getValue().equals("00000") ? "11111" : "00000"))
                .isInstanceOf(ApiException.class).hasMessageContaining("4 tries left");
        EmailVerifiedResponse verified = service.confirm(EMAIL, code.getValue());
        assertThat(verification.getTokenHash()).isEqualTo(SecureTokens.sha256Hex(verified.verificationToken()));

        when(repository.findByTokenHash(verification.getTokenHash())).thenReturn(Optional.of(verification));
        service.consume(EMAIL, verified.verificationToken(), "personalEmailVerificationToken");
        assertThat(verification.getConsumedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.consume(EMAIL, verified.verificationToken(), "personalEmailVerificationToken"))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void anAddressInUseOrAskedForJustNowGetsNoCode() {
        when(users.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);
        assertThatThrownBy(() -> service.requestCode(EMAIL, null, 1L)).isInstanceOf(ApiException.class);

        when(users.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
        when(repository.findFirstByEmailOrderByCreatedAtDescIdDesc(EMAIL))
                .thenReturn(Optional.of(EmailVerification.builder().email(EMAIL).createdAt(NOW.minusSeconds(15)).build()));
        assertThatThrownBy(() -> service.requestCode(EMAIL, null, 1L)).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("VERIFICATION_TOO_SOON");
        verifyNoInteractions(mailer);
    }
}
