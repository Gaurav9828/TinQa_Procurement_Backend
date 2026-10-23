package com.tinqa.procurement.payroll;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Monthly salaries. Salaries are transferred from the bank outside the system; here the payments team records that a
 * month's salary was transferred (one employee, or everyone still unpaid at once), with any extra earnings or
 * deductions of that month, and a Level 2 approver other than the recorder confirms it. Only then is the payment
 * booked to the company account (the e-commerce service reads the approved payments), emailed to the employee as a
 * payslip and downloadable by them. Payslips are rendered from the payment whenever asked for, never stored.
 *
 * <p>Batches are all or nothing: if any employee of a batch can't be recorded or decided, nothing is.
 */
@Service
@RequiredArgsConstructor
public class PayrollService {

    static final Set<SalaryPaymentStatus> LIVE = EnumSet.of(SalaryPaymentStatus.PENDING_APPROVAL, SalaryPaymentStatus.APPROVED);
    static final String PAYROLL_CURRENCY = "INR";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final SalaryPaymentRepository paymentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationService notificationService;
    private final PayslipComposer payslipComposer;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PayrollDTOs.YearSummary yearSummary(int year) {
        checkYear(year);
        YearMonth current = PayrollPeriods.currentMonth(clock);
        List<Employee> employees = employeeRepository.findAll();
        Map<Integer, List<SalaryPayment>> liveByMonth = paymentRepository.findYearInStatus(year, LIVE).stream()
                .collect(Collectors.groupingBy(SalaryPayment::getPayMonth));

        List<PayrollDTOs.MonthSummary> months = new ArrayList<>();
        BigDecimal yearApproved = ZERO;
        for (int month = 1; month <= 12; month++) {
            YearMonth period = YearMonth.of(year, month);
            List<SalaryPayment> live = liveByMonth.getOrDefault(month, List.of());
            Set<Long> recorded = live.stream().map(SalaryPayment::getEmployeeId).collect(Collectors.toSet());
            List<Employee> payable = employees.stream().filter(employee -> notPayableReason(employee, period) == null).toList();
            int notRecorded = (int) payable.stream().filter(employee -> !recorded.contains(employee.getId())).count();
            int pending = (int) live.stream().filter(p -> p.getStatus() == SalaryPaymentStatus.PENDING_APPROVAL).count();
            List<SalaryPayment> approved = live.stream().filter(p -> p.getStatus() == SalaryPaymentStatus.APPROVED).toList();
            BigDecimal approvedNet = sum(approved);
            yearApproved = yearApproved.add(approvedNet);
            months.add(PayrollDTOs.MonthSummary.builder()
                    .month(month)
                    .state(stateOf(period, current, payable.size(), notRecorded, pending, approved.size()))
                    .payableEmployees(payable.size())
                    .pendingApproval(pending)
                    .approved(approved.size())
                    .approvedNetPay(approvedNet)
                    .build());
        }
        return PayrollDTOs.YearSummary.builder().year(year).months(months).approvedNetPay(yearApproved).build();
    }

    @Transactional(readOnly = true)
    public PayrollDTOs.MonthView monthView(int year, int month) {
        YearMonth period = period(year, month);
        List<SalaryPayment> payments = paymentRepository.findMonth(year, month);
        // Each employee's live payment, else their latest rejected / withdrawn one (payments come oldest first)
        Map<Long, SalaryPayment> latest = new HashMap<>();
        for (SalaryPayment payment : payments) {
            SalaryPayment kept = latest.get(payment.getEmployeeId());
            if (kept == null || !LIVE.contains(kept.getStatus())) {
                latest.put(payment.getEmployeeId(), payment);
            }
        }
        Map<Long, User> users = usersOf(payments);

        List<PayrollDTOs.MonthRow> rows = new ArrayList<>();
        int payableCount = 0;
        int notRecorded = 0;
        for (Employee employee : employeeRepository.findAll()) {
            String reason = notPayableReason(employee, period);
            SalaryPayment payment = latest.get(employee.getId());
            // Left out: nobody to pay (not joined yet, left earlier, deleted, not approved) and nothing recorded
            if (payment == null && reason != null && !shownWhenNotPayable(employee, period)) {
                continue;
            }
            if (reason == null) {
                payableCount++;
                if (payment == null || !LIVE.contains(payment.getStatus())) {
                    notRecorded++;
                }
            }
            rows.add(PayrollDTOs.MonthRow.builder()
                    .employeeId(employee.getId())
                    .userId(employee.getUserId())
                    .employeeCode(employee.getEmployeeCode())
                    .name(employee.getDisplayName())
                    .designation(employee.getDesignation() != null ? employee.getDesignation().label() : null)
                    .department(employee.getDepartment())
                    .monthlySalary(employee.getSalaryAmount())
                    .currency(currencyOf(employee))
                    .payable(reason == null)
                    .notPayableReason(reason)
                    .payment(payment == null ? null : toResponse(payment, users))
                    .build());
        }
        rows.sort(Comparator.comparing(PayrollDTOs.MonthRow::getEmployeeCode, Comparator.nullsLast(String::compareTo)));

        List<SalaryPayment> live = latest.values().stream().filter(p -> LIVE.contains(p.getStatus())).toList();
        List<SalaryPayment> pending = live.stream().filter(p -> p.getStatus() == SalaryPaymentStatus.PENDING_APPROVAL).toList();
        List<SalaryPayment> approved = live.stream().filter(p -> p.getStatus() == SalaryPaymentStatus.APPROVED).toList();
        return PayrollDTOs.MonthView.builder()
                .year(year)
                .month(month)
                .open(!period.isAfter(PayrollPeriods.currentMonth(clock)))
                .payableEmployees(payableCount)
                .notRecorded(notRecorded)
                .pendingApproval(pending.size())
                .approved(approved.size())
                .pendingNetPay(sum(pending))
                .approvedNetPay(sum(approved))
                .rows(rows)
                .build();
    }

    // ------------------------------------------------------------------ recording (maker)

    /** Records the month's salary of every employee in the request as transferred, for someone else to confirm. */
    @Transactional
    public List<PayrollDTOs.PaymentResponse> record(int year, int month, PayrollDTOs.RecordRequest request) {
        YearMonth period = period(year, month);
        YearMonth current = PayrollPeriods.currentMonth(clock);
        if (period.isAfter(current)) {
            throw ApiErrors.conflict(ErrorCodes.SALARY_MONTH_NOT_OPEN,
                    PayrollPeriods.label(period) + " hasn't started yet, so its salaries can't be recorded.");
        }
        LocalDate today = LocalDate.now(clock.withZone(PayrollPeriods.BUSINESS_ZONE));
        if (request.getTransferDate().isAfter(today)) {
            throw ApiErrors.fieldError("transferDate", "The transfer date can't be in the future.");
        }
        if (request.getTransferDate().isBefore(period.atDay(1))) {
            throw ApiErrors.fieldError("transferDate", "The transfer date can't be before " + PayrollPeriods.label(period) + " starts.");
        }

        User recorder = currentUserProvider.getCurrentUser();
        List<Long> ids = request.getEntries().stream().map(PayrollDTOs.Entry::getEmployeeId).toList();
        Map<Long, Employee> employees = employeeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));

        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < request.getEntries().size(); i++) {
            Long employeeId = ids.get(i);
            String field = "entries[" + i + "]";
            Employee employee = employees.get(employeeId);
            if (employee == null) {
                errors.add(ApiErrors.error(field, "Employee " + employeeId + " was not found."));
                continue;
            }
            if (!seen.add(employeeId)) {
                errors.add(ApiErrors.error(field, employee.getDisplayName() + " is listed twice."));
                continue;
            }
            String reason = notPayableReason(employee, period);
            if (reason != null) {
                errors.add(ApiErrors.error(field, employee.getDisplayName() + ": " + reason));
            } else if (paymentRepository.existsInStatus(employeeId, year, month, LIVE)) {
                errors.add(ApiErrors.error(field, employee.getDisplayName() + "'s salary for " + PayrollPeriods.label(period)
                        + " is already recorded."));
            } else if (netPay(employee, request.getEntries().get(i)).signum() <= 0) {
                errors.add(ApiErrors.error(field, employee.getDisplayName() + ": the deductions leave nothing to pay."));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(errors.size() == 1 ? errors.getFirst().getMessage()
                    : errors.size() + " salaries can't be recorded: " + ApiErrors.joinMessages(errors),
                    ErrorCodes.SALARY_NOT_PAYABLE, HttpStatus.CONFLICT, errors);
        }

        Instant now = clock.instant();
        String sharedReference = OptionalText.blankToNull(request.getBankReference());
        String note = OptionalText.blankToNull(request.getNote());
        List<SalaryPayment> saved = new ArrayList<>();
        try {
            for (PayrollDTOs.Entry entry : request.getEntries()) {
                Employee employee = employees.get(entry.getEmployeeId());
                String reference = OptionalText.blankToNull(entry.getBankReference());
                saved.add(paymentRepository.save(newPayment(employee, period, entry, request.getTransferDate(),
                        reference != null ? reference : sharedReference, note, recorder.getId(), now)));
            }
            paymentRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            // Someone recorded one of these employees for the month between our check and the insert
            throw ApiErrors.conflict(ErrorCodes.SALARY_ALREADY_RECORDED,
                    "Some of these salaries were just recorded by someone else. Reload the month and try again.");
        }

        notificationService.createForPermission(Permission.PAYMENT_APPROVE, recorder.getId(), "Salaries awaiting confirmation",
                saved.size() + (saved.size() == 1 ? " salary" : " salaries") + " for " + PayrollPeriods.label(period) + " ("
                        + PayrollPeriods.rupees(sum(saved)) + " in all) were recorded as transferred by " + recorder.getUsername()
                        + " and wait for someone else to confirm them.", NotificationLinks.PAYROLL);
        Map<Long, User> users = usersOf(saved);
        return saved.stream().map(payment -> toResponse(payment, users)).toList();
    }

    /** Takes back a recorded salary nobody has decided on yet (e.g. recorded by mistake). */
    @Transactional
    public PayrollDTOs.PaymentResponse withdraw(Long paymentId) {
        SalaryPayment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary payment not found with id: " + paymentId));
        if (payment.getStatus() != SalaryPaymentStatus.PENDING_APPROVAL) {
            throw ApiErrors.conflict(ErrorCodes.SALARY_NOT_PENDING, "This salary is " + label(payment.getStatus())
                    + ", so it can't be withdrawn.");
        }
        User actor = currentUserProvider.getCurrentUser();
        Instant now = clock.instant();
        payment.setStatus(SalaryPaymentStatus.WITHDRAWN);
        payment.setDecidedBy(actor.getId());
        payment.setDecidedAt(now);
        payment.setUpdatedAt(now);
        paymentRepository.save(payment);
        return toResponse(payment, usersOf(List.of(payment)));
    }

    // ------------------------------------------------------------------ deciding (checker)

    /**
     * Confirms or rejects recorded salaries. Confirming books them to the company account and queues each payslip
     * email; nobody confirms a salary they recorded, or their own.
     */
    @Transactional
    public PayrollDTOs.DecisionResult decide(PayrollDTOs.DecisionRequest request) {
        User approver = currentUserProvider.getCurrentUser();
        boolean approve = request.getDecision() == PayrollDTOs.Decision.APPROVED;
        String reason = OptionalText.blankToNull(request.getRejectionReason());
        if (!approve && reason == null) {
            throw ApiErrors.fieldError("rejectionReason", "A reason is required to reject a salary payment.");
        }

        Set<Long> ids = new HashSet<>(request.getPaymentIds());
        List<SalaryPayment> payments = paymentRepository.findAllByIdForUpdate(ids);
        if (payments.size() != ids.size()) {
            Set<Long> found = payments.stream().map(SalaryPayment::getId).collect(Collectors.toSet());
            throw new ResourceNotFoundException("Salary payment not found with id: "
                    + ids.stream().filter(id -> !found.contains(id)).sorted().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        Map<Long, Employee> employees = employeeRepository.findAllById(payments.stream().map(SalaryPayment::getEmployeeId).toList())
                .stream().collect(Collectors.toMap(Employee::getId, Function.identity()));

        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        boolean selfDecision = false;
        for (SalaryPayment payment : payments) {
            Employee employee = employees.get(payment.getEmployeeId());
            String who = employee.getDisplayName() + " (" + PayrollPeriods.label(periodOf(payment)) + ")";
            if (payment.getStatus() != SalaryPaymentStatus.PENDING_APPROVAL) {
                errors.add(ApiErrors.error("paymentIds", who + " is " + label(payment.getStatus()) + ", not waiting for confirmation."));
            } else if (payment.getRecordedBy().equals(approver.getId())) {
                // Maker-checker: whoever recorded a salary never decides on it
                errors.add(ApiErrors.error("paymentIds", "You recorded " + who + ", so someone else has to decide on it."));
                selfDecision = true;
            } else if (Objects.equals(employee.getUserId(), approver.getId())) {
                errors.add(ApiErrors.error("paymentIds", "That is your own salary (" + PayrollPeriods.label(periodOf(payment))
                        + "), so someone else has to decide on it."));
                selfDecision = true;
            }
        }
        if (!errors.isEmpty()) {
            String message = errors.size() == 1 ? errors.getFirst().getMessage() : ApiErrors.joinMessages(errors);
            throw selfDecision
                    ? new ApiException(message, ErrorCodes.SALARY_SELF_DECISION, HttpStatus.FORBIDDEN, errors)
                    : new ApiException(message, ErrorCodes.SALARY_NOT_PENDING, HttpStatus.CONFLICT, errors);
        }

        Instant now = clock.instant();
        Map<Long, User> accounts = userRepository.findAllById(employees.values().stream().map(Employee::getUserId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        for (SalaryPayment payment : payments) {
            payment.setStatus(approve ? SalaryPaymentStatus.APPROVED : SalaryPaymentStatus.REJECTED);
            payment.setDecidedBy(approver.getId());
            payment.setDecidedAt(now);
            payment.setRejectionReason(approve ? null : reason);
            payment.setUpdatedAt(now);
            if (approve) {
                Employee employee = employees.get(payment.getEmployeeId());
                // Emailed by PayslipEmailJob after this commits
                payment.setPayslipEmailTo(PayslipEmails.recipient(employee, accounts.get(employee.getUserId())));
            }
        }
        paymentRepository.saveAll(payments);
        notifyDecision(payments, employees, approver, approve, reason);

        Map<Long, User> users = usersOf(payments);
        return PayrollDTOs.DecisionResult.builder()
                .decided(payments.size())
                .payments(payments.stream().map(payment -> toResponse(payment, users)).toList())
                .build();
    }

    /** Emails a confirmed payslip again (to the employee's current address), e.g. after it bounced or was lost. */
    @Transactional
    public PayrollDTOs.PaymentResponse resendPayslip(Long paymentId) {
        SalaryPayment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary payment not found with id: " + paymentId));
        requireApproved(payment);
        Employee employee = employeeRepository.findById(payment.getEmployeeId()).orElseThrow();
        User account = employee.getUserId() == null ? null : userRepository.findById(employee.getUserId()).orElse(null);
        payment.setPayslipEmailTo(PayslipEmails.recipient(employee, account));
        payment.setPayslipEmailedAt(null);
        payment.setPayslipEmailAttempts(0);
        payment.setPayslipEmailLastAttemptAt(null);
        payment.setPayslipEmailError(null);
        payment.setUpdatedAt(clock.instant());
        paymentRepository.save(payment);
        return toResponse(payment, usersOf(List.of(payment)));
    }

    // ------------------------------------------------------------------ payslips

    /** Any employee's payslip, for the payments team. */
    @Transactional(readOnly = true)
    public PayslipComposer.PayslipFile payslip(Long paymentId) {
        SalaryPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary payment not found with id: " + paymentId));
        requireApproved(payment);
        return payslipComposer.render(payment);
    }

    /** The signed-in employee's confirmed months, newest first. */
    @Transactional(readOnly = true)
    public List<PayrollDTOs.MyPayslip> myPayslips() {
        return currentEmployee()
                .map(employee -> paymentRepository.findByEmployeeAndStatus(employee.getId(), SalaryPaymentStatus.APPROVED))
                .orElse(List.of())
                .stream()
                .map(payment -> PayrollDTOs.MyPayslip.builder()
                        .id(payment.getId())
                        .payYear(payment.getPayYear())
                        .payMonth(payment.getPayMonth())
                        .currency(payment.getCurrency())
                        .totalEarnings(payment.getTotalEarnings())
                        .totalDeductions(payment.getTotalDeductions())
                        .netPay(payment.getNetPay())
                        .transferDate(payment.getTransferDate())
                        .approvedAt(payment.getDecidedAt())
                        .build())
                .toList();
    }

    /** One of the signed-in employee's own confirmed payslips; anyone else's (or an unconfirmed one) is "not found". */
    @Transactional(readOnly = true)
    public PayslipComposer.PayslipFile myPayslip(Long paymentId) {
        Employee employee = currentEmployee().orElseThrow(() -> new ResourceNotFoundException("Payslip not found"));
        SalaryPayment payment = paymentRepository.findById(paymentId)
                .filter(found -> found.getEmployeeId().equals(employee.getId()))
                .filter(found -> found.getStatus() == SalaryPaymentStatus.APPROVED)
                .orElseThrow(() -> new ResourceNotFoundException("Payslip not found"));
        return payslipComposer.render(payment);
    }

    // ------------------------------------------------------------------ rules

    /**
     * Why no salary can be recorded for the employee in the month, or null when it can: they must be an approved,
     * not deleted employee working at some point of the month, with a monthly salary in rupees.
     */
    static String notPayableReason(Employee employee, YearMonth period) {
        if (employee.getStatus() == EmployeeStatus.DELETED) {
            return "Deleted employees can't be paid.";
        }
        if (employee.getStatus() == EmployeeStatus.APPROVAL_PENDING) {
            return "The employee's account is still waiting for approval.";
        }
        if (employee.getJoiningDate() != null && employee.getJoiningDate().isAfter(period.atEndOfMonth())) {
            return "Joins after " + PayrollPeriods.label(period) + ".";
        }
        if (employee.getLeavingDate() != null && employee.getLeavingDate().isBefore(period.atDay(1))) {
            return "Left before " + PayrollPeriods.label(period) + ".";
        }
        if (employee.getSalaryAmount() == null || employee.getSalaryAmount().signum() <= 0) {
            return "No monthly salary is set. Add it in Employee Management first.";
        }
        if (!PAYROLL_CURRENCY.equals(currencyOf(employee))) {
            return "The salary is in " + currencyOf(employee) + "; only salaries in INR can be paid here.";
        }
        return null;
    }

    // Employees working in the month whose salary details are missing are listed, so they can be fixed
    private static boolean shownWhenNotPayable(Employee employee, YearMonth period) {
        return employee.getStatus() != EmployeeStatus.DELETED && employee.getStatus() != EmployeeStatus.APPROVAL_PENDING
                && (employee.getJoiningDate() == null || !employee.getJoiningDate().isAfter(period.atEndOfMonth()))
                && (employee.getLeavingDate() == null || !employee.getLeavingDate().isBefore(period.atDay(1)));
    }

    private static PayrollDTOs.MonthState stateOf(YearMonth period, YearMonth current, int payable, int notRecorded,
                                                  int pending, int approved) {
        if (period.isAfter(current)) {
            return PayrollDTOs.MonthState.UPCOMING;
        }
        if (payable == 0 && pending == 0 && approved == 0) {
            return PayrollDTOs.MonthState.NO_EMPLOYEES;
        }
        if (pending == 0 && approved == 0) {
            return PayrollDTOs.MonthState.NOT_STARTED;
        }
        if (notRecorded > 0) {
            return PayrollDTOs.MonthState.IN_PROGRESS;
        }
        return pending > 0 ? PayrollDTOs.MonthState.AWAITING_APPROVAL : PayrollDTOs.MonthState.PAID;
    }

    private static SalaryPayment newPayment(Employee employee, YearMonth period, PayrollDTOs.Entry entry, LocalDate transferDate,
                                            String reference, String note, Long recordedBy, Instant now) {
        BigDecimal monthly = money(employee.getSalaryAmount());
        SalaryPayment payment = SalaryPayment.builder()
                .employeeId(employee.getId())
                .payYear(period.getYear())
                .payMonth(period.getMonthValue())
                .status(SalaryPaymentStatus.PENDING_APPROVAL)
                .currency(PAYROLL_CURRENCY)
                .monthlySalary(monthly)
                .totalEarnings(monthly.add(lineTotal(entry, SalaryLineKind.EARNING)))
                .totalDeductions(lineTotal(entry, SalaryLineKind.DEDUCTION))
                .netPay(netPay(employee, entry))
                .designation(employee.getDesignation() != null ? employee.getDesignation().label() : "-")
                .department(employee.getDepartment())
                .transferDate(transferDate)
                .bankReference(reference)
                .note(note)
                .recordedBy(recordedBy)
                .recordedAt(now)
                .updatedAt(now)
                .build();
        List<PayrollDTOs.Line> lines = entry.getLines() == null ? List.of() : entry.getLines();
        for (int i = 0; i < lines.size(); i++) {
            PayrollDTOs.Line line = lines.get(i);
            payment.getLines().add(SalaryPaymentLine.builder()
                    .payment(payment)
                    .kind(line.getKind())
                    .label(line.getLabel().trim())
                    .amount(money(line.getAmount()))
                    .position(i)
                    .build());
        }
        return payment;
    }

    private static BigDecimal netPay(Employee employee, PayrollDTOs.Entry entry) {
        return money(employee.getSalaryAmount()).add(lineTotal(entry, SalaryLineKind.EARNING))
                .subtract(lineTotal(entry, SalaryLineKind.DEDUCTION));
    }

    private static BigDecimal lineTotal(PayrollDTOs.Entry entry, SalaryLineKind kind) {
        return (entry.getLines() == null ? Stream.<PayrollDTOs.Line>empty() : entry.getLines().stream())
                .filter(line -> line.getKind() == kind)
                .map(line -> money(line.getAmount()))
                .reduce(ZERO, BigDecimal::add);
    }

    private void notifyDecision(List<SalaryPayment> payments, Map<Long, Employee> employees, User approver, boolean approve,
                                String reason) {
        if (approve) {
            for (SalaryPayment payment : payments) {
                Employee employee = employees.get(payment.getEmployeeId());
                if (employee.getUserId() != null) {
                    notificationService.createForUser(employee.getUserId(), "Your payslip for "
                                    + PayrollPeriods.label(periodOf(payment)) + " is ready",
                            "Your salary of " + PayrollPeriods.rupees(payment.getNetPay()) + " for " + PayrollPeriods.label(periodOf(payment))
                                    + " was transferred on " + PayrollPeriods.date(payment.getTransferDate())
                                    + ". Download the payslip from My Payslips; it is also emailed to you.",
                            NotificationLinks.MY_PAYSLIPS);
                }
            }
        }
        // One note per recorder of the batch
        Map<Long, List<SalaryPayment>> byRecorder = payments.stream().collect(Collectors.groupingBy(SalaryPayment::getRecordedBy,
                LinkedHashMap::new, Collectors.toList()));
        byRecorder.forEach((recorderId, recorded) -> notificationService.createForUser(recorderId,
                approve ? "Salaries confirmed" : "Salaries rejected",
                recorded.size() + (recorded.size() == 1 ? " salary" : " salaries") + " you recorded ("
                        + recorded.stream().map(p -> employees.get(p.getEmployeeId()).getDisplayName() + ", "
                        + PayrollPeriods.label(periodOf(p))).collect(Collectors.joining("; ")) + ") "
                        + (approve ? "were confirmed by " + approver.getUsername() + ". Payslips are being emailed."
                        : "were rejected by " + approver.getUsername() + ". Reason: " + reason + ". Record them again once corrected."),
                NotificationLinks.PAYROLL));
    }

    private static void requireApproved(SalaryPayment payment) {
        if (payment.getStatus() != SalaryPaymentStatus.APPROVED) {
            throw ApiErrors.conflict(ErrorCodes.SALARY_NOT_APPROVED, "This salary is " + label(payment.getStatus())
                    + ". A payslip exists only once the salary is confirmed.");
        }
    }

    private Optional<Employee> currentEmployee() {
        return employeeRepository.findByUserId(currentUserProvider.getCurrentUserId());
    }

    private static YearMonth period(int year, int month) {
        checkYear(year);
        if (month < 1 || month > 12) {
            throw ApiErrors.fieldError("month", "Month must be between 1 and 12.");
        }
        return YearMonth.of(year, month);
    }

    private static void checkYear(int year) {
        if (year < 2000 || year > 2100) {
            throw ApiErrors.fieldError("year", "Year must be between 2000 and 2100.");
        }
    }

    static YearMonth periodOf(SalaryPayment payment) {
        return YearMonth.of(payment.getPayYear(), payment.getPayMonth());
    }

    static String currencyOf(Employee employee) {
        return employee.getSalaryCurrency() == null || employee.getSalaryCurrency().isBlank()
                ? PAYROLL_CURRENCY : employee.getSalaryCurrency().trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<SalaryPayment> payments) {
        return payments.stream().map(SalaryPayment::getNetPay).reduce(ZERO, BigDecimal::add);
    }

    private static String label(SalaryPaymentStatus status) {
        return switch (status) {
            case PENDING_APPROVAL -> "waiting for confirmation";
            case APPROVED -> "already confirmed";
            case REJECTED -> "rejected";
            case WITHDRAWN -> "withdrawn";
        };
    }

    private Map<Long, User> usersOf(List<SalaryPayment> payments) {
        Set<Long> ids = payments.stream().flatMap(p -> Stream.of(p.getRecordedBy(), p.getDecidedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    static PayrollDTOs.PaymentResponse toResponse(SalaryPayment payment, Map<Long, User> users) {
        return PayrollDTOs.PaymentResponse.builder()
                .id(payment.getId())
                .employeeId(payment.getEmployeeId())
                .payYear(payment.getPayYear())
                .payMonth(payment.getPayMonth())
                .status(payment.getStatus())
                .currency(payment.getCurrency())
                .monthlySalary(payment.getMonthlySalary())
                .totalEarnings(payment.getTotalEarnings())
                .totalDeductions(payment.getTotalDeductions())
                .netPay(payment.getNetPay())
                .lines(payment.getLines().stream().map(line -> PayrollDTOs.LineResponse.builder()
                        .kind(line.getKind()).label(line.getLabel()).amount(line.getAmount()).build()).toList())
                .transferDate(payment.getTransferDate())
                .bankReference(payment.getBankReference())
                .note(payment.getNote())
                .recordedBy(payment.getRecordedBy())
                .recordedByName(usernameOf(users, payment.getRecordedBy()))
                .recordedAt(payment.getRecordedAt())
                .decidedByName(usernameOf(users, payment.getDecidedBy()))
                .decidedAt(payment.getDecidedAt())
                .rejectionReason(payment.getRejectionReason())
                .payslipEmailTo(payment.getPayslipEmailTo())
                .payslipEmailedAt(payment.getPayslipEmailedAt())
                .payslipEmailError(payment.getPayslipEmailError())
                .payslipEmailAttempts(payment.getPayslipEmailAttempts() == null ? 0 : payment.getPayslipEmailAttempts())
                .build();
    }

    private static String usernameOf(Map<Long, User> users, Long userId) {
        User user = userId == null ? null : users.get(userId);
        return user != null ? user.getUsername() : null;
    }
}
