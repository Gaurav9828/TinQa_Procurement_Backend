package com.tinqa.procurement.employee.service.impl;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.employee.constants.EmployeeConstants;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.employee.dto.EmployeeApprovalDecisionRequest;
import com.tinqa.procurement.employee.dto.UpdateEmployeeRequest;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.employee.service.EmployeeContactValidator;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployeeServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private NotificationService notificationService;

    private EmployeeServiceImpl service;

    private final User l1 = User.builder().id(1L).username("admin_l1").role(Role.ADMIN_L1).build();
    private final User l2 = User.builder().id(2L).username("admin_l2").role(Role.ADMIN_L2).build();
    private final User otherL2 = User.builder().id(3L).username("procurement_mgr").role(Role.ADMIN_L2).build();

    @BeforeEach
    void setUp() {
        EmployeeContactValidator validator = new EmployeeContactValidator(employeeRepository, userRepository);
        service = new EmployeeServiceImpl(userRepository, employeeRepository, passwordEncoder, currentUserProvider, validator,
                notificationService);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(50L);
            return user;
        });
        when(employeeRepository.saveAndFlush(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(employeeRepository.findUsingAnyPhone(any(), anyLong())).thenReturn(List.of());
        when(employeeRepository.findFirstByPersonalEmailIgnoreCaseAndIdNot(any(), anyLong())).thenReturn(Optional.empty());
        when(employeeRepository.findHighestEmployeeCodeNumber()).thenReturn(7);
    }

    private CreateEmployeeRequest request(String role) {
        return CreateEmployeeRequest.builder()
                .username("Rohan.Mehta").email("Rohan@TinQa.com").firstName("Rohan").lastName("Mehta")
                .dateOfBirth(LocalDate.of(1995, 11, 10)).gender("MALE").designation("Buyer").department("Supply Chain")
                .employmentType("FULL_TIME").joiningDate(LocalDate.of(2026, 2, 15))
                .phone("98765 43299").personalEmail("Rohan.Personal@Example.com").role(role)
                .build();
    }

    // ---------- create: approval rule ----------

    @Test
    void employeeCreatedByL1WaitsForApprovalAndCannotSignIn() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l1);

        var response = service.createEmployee(request("ADMIN_L1"));

        assertEquals(EmployeeConstants.APPROVAL_PENDING, response.getStatus());
        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(user.capture());
        assertFalse(user.getValue().isEnabled());
        verify(notificationService).createForRole(eq(Role.ADMIN_L2), eq(1L), eq("Employee awaiting approval"), contains("created by admin_l1"));
    }

    @Test
    void employeeCreatedByL2IsActiveImmediately() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);

        var response = service.createEmployee(request("ADMIN_L1"));

        assertEquals(EmployeeConstants.FIRST_LOGIN, response.getStatus());
        verifyNoInteractions(notificationService);
    }

    @Test
    void newAdminL2AccountAlwaysNeedsASecondL2() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);

        assertEquals(EmployeeConstants.APPROVAL_PENDING, service.createEmployee(request("ADMIN_L2")).getStatus());
    }

    @Test
    void createStoresNormalisedContactsSubmittedEmailAndNextFreeCode() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);

        var response = service.createEmployee(request("ADMIN_L1"));

        assertEquals("EMP-0008", response.getEmployeeCode());
        assertEquals("+919876543299", response.getPhone());
        assertEquals("rohan.personal@example.com", response.getPersonalEmail());
        assertEquals("rohan@tinqa.com", response.getWorkEmail());
        assertEquals(2L, response.getCreatedBy());
        verify(employeeRepository).lockEmployeeCodeSequence();
    }

    // ---------- create: duplicates and field rules ----------

    @Test
    void everyDuplicateIsReportedOnItsOwnField() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        when(userRepository.existsByUsernameIgnoreCase("Rohan.Mehta")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCase("Rohan@TinQa.com")).thenReturn(true);
        when(employeeRepository.findUsingAnyPhone(eq(List.of("+919876543299", "9876543299", "09876543299", "919876543299")), eq(-1L)))
                .thenReturn(List.of(Employee.builder().employeeCode("EMP-0004").build()));
        when(employeeRepository.findFirstByPersonalEmailIgnoreCaseAndIdNot("Rohan.Personal@Example.com", -1L))
                .thenReturn(Optional.of(Employee.builder().employeeCode("EMP-0005").build()));

        ApiException exception = assertThrows(ApiException.class, () -> service.createEmployee(request("ADMIN_L1")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("DUPLICATE_VALUE", exception.getErrorCode());
        assertEquals(List.of("username", "email", "phone", "personalEmail"),
                exception.getErrors().stream().map(e -> e.getField()).toList());
        assertEquals("Phone number +919876543299 is already registered to employee EMP-0004.", exception.getErrors().get(2).getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void alternatePhoneMustDifferFromPhoneInAnySpelling() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        CreateEmployeeRequest request = request("ADMIN_L1");
        request.setAlternatePhone("+91 98765-43299");

        ApiException exception = assertThrows(ApiException.class, () -> service.createEmployee(request));

        assertEquals("alternatePhone", exception.getErrors().getFirst().getField());
    }

    @Test
    void joiningBeforeEighteenthBirthdayIsRejected() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        CreateEmployeeRequest request = request("ADMIN_L1");
        request.setJoiningDate(LocalDate.of(2010, 1, 1));

        ApiException exception = assertThrows(ApiException.class, () -> service.createEmployee(request));

        assertEquals("VALIDATION_FAILED", exception.getErrorCode());
        assertEquals("Joining date must be on or after the employee's 18th birthday (2013-11-10).", exception.getMessage());
    }

    // ---------- approval decision ----------

    private Employee pending(Long createdBy) {
        return Employee.builder().id(9L).userId(50L).employeeCode("EMP-0009").displayName("Rohan Mehta")
                .status(EmployeeConstants.APPROVAL_PENDING).createdBy(createdBy).build();
    }

    private User account(boolean firstLogin) {
        User user = User.builder().id(50L).username("rohan.mehta").enabled(false).build();
        user.setFirstLogin(firstLogin);
        return user;
    }

    @Test
    void approvalEnablesTheAccountAndNotifiesTheCreator() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        when(employeeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(pending(1L)));
        User account = account(true);
        when(userRepository.findById(50L)).thenReturn(Optional.of(account));

        var response = service.decideApproval(9L, EmployeeApprovalDecisionRequest.builder().decision("APPROVED").build());

        assertTrue(account.isEnabled());
        assertEquals(EmployeeConstants.FIRST_LOGIN, response.getStatus());
        assertEquals(2L, response.getApprovalDecidedBy());
        verify(notificationService).createForUser(eq(1L), eq("Employee approved"), contains("can now sign in"));
    }

    @Test
    void approvingAnOlderRecordWhoseOwnerAlreadySetAPasswordMakesThemActive() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        when(employeeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(pending(null)));
        when(userRepository.findById(50L)).thenReturn(Optional.of(account(false)));

        assertEquals(EmployeeConstants.ACTIVE,
                service.decideApproval(9L, EmployeeApprovalDecisionRequest.builder().decision("APPROVED").build()).getStatus());
    }

    @Test
    void rejectionDeactivatesAndKeepsTheReason() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        when(employeeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(pending(1L)));
        User account = account(true);
        account.setEnabled(true);
        when(userRepository.findById(50L)).thenReturn(Optional.of(account));

        var response = service.decideApproval(9L,
                EmployeeApprovalDecisionRequest.builder().decision("REJECTED").reason("  Duplicate of EMP-0004 ").build());

        assertFalse(account.isEnabled());
        assertEquals(EmployeeConstants.IN_ACTIVE, response.getStatus());
        assertEquals("Duplicate of EMP-0004", response.getApprovalRejectionReason());
    }

    @Test
    void rejectionNeedsAReason() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        ApiException exception = assertThrows(ApiException.class,
                () -> service.decideApproval(9L, EmployeeApprovalDecisionRequest.builder().decision("REJECTED").reason(" ").build()));
        assertEquals("reason", exception.getErrors().getFirst().getField());
    }

    @Test
    void creatorCannotApproveTheirOwnEmployee() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        when(employeeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(pending(2L)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.decideApproval(9L, EmployeeApprovalDecisionRequest.builder().decision("APPROVED").build()));

        assertEquals("EMPLOYEE_APPROVAL_SELF_DECISION", exception.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void onlyPendingEmployeesCanBeDecided() {
        when(currentUserProvider.getCurrentUser()).thenReturn(otherL2);
        Employee active = pending(1L);
        active.setStatus(EmployeeConstants.ACTIVE);
        when(employeeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(active));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.decideApproval(9L, EmployeeApprovalDecisionRequest.builder().decision("APPROVED").build()));

        assertEquals("EMPLOYEE_NOT_PENDING_APPROVAL", exception.getErrorCode());
    }

    // ---------- update ----------

    @Test
    void updateCannotBypassApprovalByChangingStatus() {
        when(employeeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(pending(1L)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.updateEmployee(9L, UpdateEmployeeRequest.builder().status(EmployeeConstants.ACTIVE).build()));

        assertEquals("status", exception.getErrors().getFirst().getField());
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void updateRejectsAPhoneOwnedByAnotherEmployee() {
        Employee employee = pending(1L);
        employee.setStatus(EmployeeConstants.ACTIVE);
        when(employeeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(employee));
        when(employeeRepository.findUsingAnyPhone(any(), eq(9L)))
                .thenReturn(List.of(Employee.builder().employeeCode("EMP-0002").build()));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.updateEmployee(9L, UpdateEmployeeRequest.builder().phone("9876543211").build()));

        assertEquals("DUPLICATE_VALUE", exception.getErrorCode());
        assertEquals("phone", exception.getErrors().getFirst().getField());
    }

    // ---------- middle name ----------

    @Test
    void blankMiddleNameIsStoredAsNoneAndLeftOutOfTheDisplayName() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        CreateEmployeeRequest request = request("ADMIN_L1");
        request.setMiddleName("   ");

        var response = service.createEmployee(request);

        assertNull(response.getMiddleName());
        assertEquals("Rohan Mehta", response.getDisplayName());
    }

    @Test
    void middleNameIsTrimmedAndUsedInTheDisplayName() {
        when(currentUserProvider.getCurrentUser()).thenReturn(l2);
        CreateEmployeeRequest request = request("ADMIN_L1");
        request.setMiddleName(" Singh ");

        var response = service.createEmployee(request);

        assertEquals("Singh", response.getMiddleName());
        assertEquals("Rohan Singh Mehta", response.getDisplayName());
    }

    @Test
    void blankMiddleNameOnUpdateRemovesItAndOmittedLeavesIt() {
        Employee employee = pending(1L);
        employee.setStatus(EmployeeConstants.ACTIVE);
        employee.setMiddleName("Kumar");
        when(employeeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(employee));

        service.updateEmployee(9L, UpdateEmployeeRequest.builder().designation("Senior Buyer").build());
        assertEquals("Kumar", employee.getMiddleName());

        service.updateEmployee(9L, UpdateEmployeeRequest.builder().middleName("").build());
        assertNull(employee.getMiddleName());
    }
}
