package com.tinqa.procurement.security.password;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.util.SecureTokens;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.mail.StaffMailException;
import com.tinqa.procurement.mail.StaffMailer;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.audit.AuthAuditEventType;
import com.tinqa.procurement.security.config.AccountEmailProperties;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.password.dto.PasswordLinkResetRequest;
import com.tinqa.procurement.security.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

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

class PasswordLinkServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private final PasswordLinkRepository links = mock(PasswordLinkRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final StaffMailer mailer = mock(StaffMailer.class);
    private final PasswordUpdater updater = mock(PasswordUpdater.class);
    private PasswordLinkService service;
    private final User priya = User.builder().id(9L).username("priya").email("priya@tinqa.com").enabled(true).accountNonLocked(true).build();

    @BeforeEach
    void setUp() {
        AccountEmailProperties properties = new AccountEmailProperties(
                new AccountEmailProperties.Verification(Duration.ofMinutes(10), 5, Duration.ofSeconds(60), 5, Duration.ofMinutes(30)),
                new AccountEmailProperties.PasswordLink(Duration.ofMinutes(15), Duration.ofSeconds(60)));
        service = new PasswordLinkService(links, users, employees, mailer, updater, mock(NotificationService.class), properties, CLOCK);
        when(users.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(employees.findFirstByPersonalEmailIgnoreCaseAndIdNot(anyString(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void aResetLinkGoesToThePersonalEmailAndIsStoredOnlyAsAHash() {
        when(users.findByUsernameIgnoreCase("Priya")).thenReturn(Optional.of(priya));
        when(employees.findByUserId(9L)).thenReturn(Optional.of(Employee.builder().firstName("Priya").personalEmail("priya@gmail.com").build()));

        service.requestReset(" Priya ");

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendPasswordLink(eq("priya@gmail.com"), eq("Priya"), token.capture(), eq(false), eq(15L));
        ArgumentCaptor<PasswordLink> saved = ArgumentCaptor.forClass(PasswordLink.class);
        verify(links).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(SecureTokens.sha256Hex(token.getValue()));
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusMinutes(15));
        verify(links).retireOpenLinks(9L, NOW);
    }

    @Test
    void unknownDisabledOrRepeatedRequestsSendNothing() {
        service.requestReset("nobody");
        when(users.findByEmailIgnoreCase("off@tinqa.com")).thenReturn(Optional.of(User.builder().id(2L).enabled(false).accountNonLocked(true).build()));
        service.requestReset("off@tinqa.com");
        when(users.findByUsernameIgnoreCase("priya")).thenReturn(Optional.of(priya));
        when(links.existsByUserIdAndPurposeAndCreatedAtAfter(eq(9L), eq(PasswordLinkPurpose.RESET), any())).thenReturn(true);
        service.requestReset("priya");

        verifyNoInteractions(mailer);
    }

    @Test
    void whenTheEmailCantBeSentTheCallerIsTold() {
        when(users.findByUsernameIgnoreCase("priya")).thenReturn(Optional.of(priya));
        when(employees.findByUserId(9L)).thenReturn(Optional.empty());
        doThrow(new StaffMailException("down", false, null)).when(mailer).sendPasswordLink(any(), any(), any(), anyBoolean(), anyLong());

        assertThatThrownBy(() -> service.requestReset("priya")).isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void anOpenLinkSetsThePasswordOnceAndAnExpiredOneIsRefused() {
        PasswordLink open = PasswordLink.builder().userId(9L).purpose(PasswordLinkPurpose.SETUP).expiresAt(NOW.plusMinutes(5)).build();
        when(links.findByTokenHashAndUsedAtIsNull(SecureTokens.sha256Hex("good"))).thenReturn(Optional.of(open));
        when(users.findById(9L)).thenReturn(Optional.of(priya));
        PasswordLinkResetRequest request = new PasswordLinkResetRequest();
        request.setToken("good");
        request.setNewPassword("Strong!Passw0rd#1");
        request.setConfirmNewPassword("Strong!Passw0rd#1");
        HttpServletRequest http = mock(HttpServletRequest.class);

        assertThat(service.check("good").purpose()).isEqualTo(PasswordLinkPurpose.SETUP);
        service.reset(request, http);
        verify(updater).apply(priya, "Strong!Passw0rd#1", AuthAuditEventType.PASSWORD_SET_BY_LINK, http);

        open.setExpiresAt(NOW);
        assertThatThrownBy(() -> service.check("good")).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("PASSWORD_LINK_EXPIRED");
        assertThatThrownBy(() -> service.check("unknown")).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("PASSWORD_LINK_INVALID");
    }
}
