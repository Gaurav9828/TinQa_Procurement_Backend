package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.AccessResolver;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.UserAccess;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Client demos and meetings: a visit to a client for a demo appointment or a promotion. It is planned as an internal
 * cost estimate (the quotation) and a Level 2 approver approves it; while it runs, the hotel, food, travel and other
 * bills are recorded (see {@link DemoBillService}); its completion is approved too, and only then is the total spent
 * booked to the company account (the e-commerce service reads {@link DemoPosting}s). Nobody approves a demo they made,
 * sent for approval or attend.
 *
 * <p>Once completed a demo is changed only by a Super Admin, who reopens it with a reason; the corrected completion is
 * approved again and just the difference to what was booked is booked.
 */
@Service
@RequiredArgsConstructor
public class ClientDemoService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_LIST = 500;
    private static final Set<DemoStatus> WAITING = EnumSet.of(DemoStatus.QUOTATION_PENDING, DemoStatus.COMPLETION_PENDING);
    private static final Set<EmployeeStatus> TAGGABLE = EnumSet.of(EmployeeStatus.ACTIVE, EmployeeStatus.FIRST_LOGIN);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    // The bills the day-by-day plan holds; other bills are added on the demo itself
    private static final Set<ExpenseKind> PLAN_KINDS = EnumSet.of(ExpenseKind.STAY, ExpenseKind.FOOD, ExpenseKind.TRAVEL);

    private final ClientDemoRepository demoRepository;
    private final DemoEventRepository eventRepository;
    private final DemoPostingRepository postingRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AccessResolver accessResolver;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final NotificationService notificationService;
    private final DemoViews views;
    private final DemoUnitPlanner unitPlanner;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    /** Demos starting within [from, to] in the given statuses (all when none), newest visit first. */
    @Transactional(readOnly = true)
    public List<ClientDemoDTOs.Summary> list(LocalDate from, LocalDate to, Collection<DemoStatus> statuses) {
        Set<DemoStatus> wanted = statuses == null || statuses.isEmpty() ? EnumSet.allOf(DemoStatus.class) : EnumSet.copyOf(statuses);
        List<ClientDemo> demos = demoRepository.search(from != null ? from : LocalDate.of(2000, 1, 1),
                to != null ? to : LocalDate.of(2100, 12, 31), wanted, PageRequest.of(0, MAX_LIST));
        return views.summaries(demos, currentUserProvider.getCurrentUserId());
    }

    /** Everything waiting for an approver's decision, oldest first (the Approvals Hub queue). */
    @Transactional(readOnly = true)
    public List<ClientDemoDTOs.Summary> waiting() {
        return views.summaries(demoRepository.findInStatus(WAITING), currentUserProvider.getCurrentUserId());
    }

    @Transactional(readOnly = true)
    public ClientDemoDTOs.Detail get(Long id) {
        return views.detail(find(id), currentUserProvider.getCurrentUser());
    }

    /**
     * Employees who can be tagged as attending staff: active, and their role goes on demos (CLIENT_DEMO_ATTENDANCE).
     * The staff list is small by design (unpaginated), and access is worked out with one query for all of them.
     */
    @Transactional(readOnly = true)
    public List<ClientDemoDTOs.StaffMember> staffOptions() {
        List<Employee> active = employeeRepository.findAll().stream()
                .filter(employee -> TAGGABLE.contains(employee.getStatus()) && employee.getUserId() != null)
                .toList();
        Set<Long> attending = attendingEmployeeIds(active);
        return active.stream()
                .filter(employee -> attending.contains(employee.getId()))
                .map(DemoViews::staffMember)
                .sorted(Comparator.comparing(ClientDemoDTOs.StaffMember::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** The demos the signed-in employee attends (drafts are not shown until they are sent for approval). */
    @Transactional(readOnly = true)
    public List<ClientDemoDTOs.Summary> mine() {
        User caller = currentUserProvider.getCurrentUser();
        return employeeRepository.findByUserId(caller.getId())
                .map(employee -> views.summaries(demoRepository.findAttendedBy(employee.getId(), DemoStatus.DRAFT), caller.getId()))
                .orElse(List.of());
    }

    /** One demo the signed-in employee attends; any other is "not found". */
    @Transactional(readOnly = true)
    public ClientDemoDTOs.Detail mine(Long id) {
        User caller = currentUserProvider.getCurrentUser();
        return views.detail(attended(id, caller), caller);
    }

    // ------------------------------------------------------------------ the plan

    @Transactional
    public ClientDemoDTOs.Detail create(ClientDemoDTOs.DemoRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        Instant now = clock.instant();
        ClientDemo demo = ClientDemo.builder()
                .status(DemoStatus.DRAFT)
                .createdBy(actor.getId())
                .createdAt(now)
                .build();
        apply(demo, request, Set.of());
        List<PlanEvent> billEvents = syncPlan(demo, request.getPlannedBills(), actor, null, now);
        applyExtraBudget(demo, request.getExtraBudgetReason(), true);
        demo.setUpdatedAt(now);
        // The new bills' ids are in the response
        demo = demoRepository.saveAndFlush(demo);
        event(demo, DemoEventType.CREATED, actor, "Planned " + demo.getDemoType().label().toLowerCase(Locale.ROOT) + " at "
                + clientsLabel(demo) + " on " + DAY.format(demo.getStartDate()), null);
        saveEvents(demo, billEvents, actor, now);
        return views.detail(demo, actor);
    }

    /**
     * Changes the plan. Planned demos change freely; once the quotation is approved a reason is required, and a new
     * budget or number of days sends the quotation back for approval. A reopened demo changes only through a Super
     * Admin.
     */
    @Transactional
    public ClientDemoDTOs.Detail update(Long id, ClientDemoDTOs.DemoRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lock(id);
        if (!views.managesBills(demo)) {
            throw notEditable(demo);
        }
        boolean approvedBefore = demo.getStatus() == DemoStatus.APPROVED || demo.getStatus() == DemoStatus.REOPENED;
        String reason = OptionalText.blankToNull(request.getChangeReason());
        if (approvedBefore && reason == null) {
            throw ApiErrors.fieldError("changeReason", "Give a reason for changing the plan of an approved demo.");
        }
        BigDecimal oldBudget = demo.getProposedBudget();
        int oldDays = demo.getNumberOfDays();
        Instant now = clock.instant();
        String changes = apply(demo, request, Set.copyOf(demo.getStaffEmployeeIds()));
        List<PlanEvent> billEvents = syncPlan(demo, request.getPlannedBills(), actor, reason, now);
        String extraChange = applyExtraBudget(demo, request.getExtraBudgetReason(), false);
        changes = Stream.of(changes, extraChange).filter(change -> !change.isEmpty()).collect(Collectors.joining("; "));
        if (changes.isEmpty() && billEvents.isEmpty()) {
            return views.detail(demo, actor);
        }
        demo.setUpdatedAt(now);
        boolean needsApproval = demo.getStatus() == DemoStatus.APPROVED
                && (oldBudget.compareTo(demo.getProposedBudget()) != 0 || oldDays != demo.getNumberOfDays());
        // The new bills' ids are in the response
        demoRepository.saveAndFlush(demo);
        if (!changes.isEmpty()) {
            event(demo, DemoEventType.UPDATED, actor, changes, reason);
        }
        saveEvents(demo, billEvents, actor, now);
        if (needsApproval) {
            // A new budget or length is a new quotation
            sendForApproval(demo, DemoStatus.QUOTATION_PENDING, actor, now);
            event(demo, DemoEventType.QUOTATION_SUBMITTED, actor, "Sent back for approval: the budget or number of days changed", reason);
            notifyApprovers(demo, actor, "Changed demo quotation awaiting approval",
                    "The budget or length of " + describe(demo) + " was changed by " + actor.getUsername() + " (" + reason
                            + ") and waits for approval again.");
        }
        demoRepository.save(demo);
        return views.detail(demo, actor);
    }

    /** Removes a demo that was never sent for approval. */
    @Transactional
    public void deleteDraft(Long id) {
        ClientDemo demo = lock(id);
        if (demo.getStatus() != DemoStatus.DRAFT || demo.getSubmittedAt() != null) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_WRONG_STATUS, demo.reference() + " was sent for approval before, so it "
                    + "can't be deleted. Cancel it instead.");
        }
        demoRepository.delete(demo);
    }

    // ------------------------------------------------------------------ the workflow

    /** Sends the quotation (the planned cost) for approval. */
    @Transactional
    public ClientDemoDTOs.Detail submitQuotation(Long id) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lock(id);
        requireStatus(demo, "sent for approval", DemoStatus.DRAFT);
        if (demo.getStaffEmployeeIds().isEmpty()) {
            throw ApiErrors.fieldError("staffEmployeeIds", "Tag at least one attending staff member before sending the quotation.");
        }
        Instant now = clock.instant();
        sendForApproval(demo, DemoStatus.QUOTATION_PENDING, actor, now);
        demo.setRejectionReason(null);
        demoRepository.save(demo);
        event(demo, DemoEventType.QUOTATION_SUBMITTED, actor, "Budget " + rupees(demo.getProposedBudget()) + " for "
                + days(demo.getNumberOfDays()), null);
        notifyApprovers(demo, actor, "Demo quotation awaiting approval", describe(demo) + " was planned by " + actor.getUsername()
                + " with a budget of " + rupees(demo.getProposedBudget()) + " and waits for someone else to approve it.");
        return views.detail(demo, actor);
    }

    /** Sends the completed visit, with all its bills, for approval. */
    @Transactional
    public ClientDemoDTOs.Detail submitCompletion(Long id, ClientDemoDTOs.CompleteRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lock(id);
        requireStatus(demo, "sent for completion", DemoStatus.APPROVED, DemoStatus.REOPENED);
        if (!views.managesBills(demo)) {
            throw notEditable(demo);
        }
        DemoUnitService.requireAllBack(demo, "sent for completion");
        if (today().isBefore(demo.getStartDate())) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_WRONG_STATUS, demo.reference() + " starts on " + DAY.format(demo.getStartDate())
                    + ", so it can't be completed yet.");
        }
        Instant now = clock.instant();
        demo.setOutcome(request.getOutcome().trim());
        demo.setRejectionReason(null);
        sendForApproval(demo, DemoStatus.COMPLETION_PENDING, actor, now);
        demoRepository.save(demo);
        BigDecimal total = demo.total();
        event(demo, DemoEventType.COMPLETION_SUBMITTED, actor, "Total spent " + rupees(total) + " of a "
                + rupees(demo.getProposedBudget()) + " budget" + (demo.completedBefore()
                ? " (corrected; " + rupees(demo.getBookedTotal()) + " was booked)" : ""), null);
        notifyApprovers(demo, actor, demo.completedBefore() ? "Demo correction awaiting approval" : "Demo completion awaiting approval",
                describe(demo) + (demo.completedBefore() ? " was corrected" : " was completed") + " by " + actor.getUsername()
                        + ": " + rupees(total) + " spent against a budget of " + rupees(demo.getProposedBudget())
                        + ". It is booked to the company account once someone else approves it.");
        return views.detail(demo, actor);
    }

    /** Takes back a quotation or completion nobody has decided on yet. */
    @Transactional
    public ClientDemoDTOs.Detail withdraw(Long id) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lock(id);
        requireStatus(demo, "withdrawn", DemoStatus.QUOTATION_PENDING, DemoStatus.COMPLETION_PENDING);
        if (demo.getStatus() == DemoStatus.COMPLETION_PENDING && demo.completedBefore()
                && !currentAccess.has(Permission.DEMO_COMPLETED_EDIT)) {
            throw notEditable(demo);
        }
        demo.setStatus(backFrom(demo));
        demo.setUpdatedAt(clock.instant());
        demoRepository.save(demo);
        event(demo, DemoEventType.WITHDRAWN, actor, "Back to " + demo.getStatus().label(), null);
        return views.detail(demo, actor);
    }

    /**
     * Approves or rejects what the demo is waiting for. Approving a completion books the total spent (for a corrected
     * demo, only the difference to what was booked) to the company account.
     */
    @Transactional
    public ClientDemoDTOs.Detail decide(Long id, ClientDemoDTOs.DecisionRequest request) {
        User approver = currentUserProvider.getCurrentUser();
        ClientDemo demo = lock(id);
        requireStatus(demo, "decided", DemoStatus.QUOTATION_PENDING, DemoStatus.COMPLETION_PENDING);
        boolean approve = request.getDecision() == ClientDemoDTOs.Decision.APPROVED;
        String reason = OptionalText.blankToNull(request.getReason());
        if (!approve && reason == null) {
            throw ApiErrors.fieldError("reason", "A reason is required to reject.");
        }
        Set<Long> staffUsers = views.staffUserIds(demo);
        if (!views.decidable(demo, approver.getId(), staffUsers)) {
            throw ApiErrors.forbidden(ErrorCodes.DEMO_SELF_DECISION, "You planned, sent or attend " + demo.reference()
                    + ", so someone else has to decide on it.");
        }

        Instant now = clock.instant();
        boolean quotation = demo.getStatus() == DemoStatus.QUOTATION_PENDING;
        boolean correction = demo.completedBefore();
        demo.setDecidedBy(approver.getId());
        demo.setDecidedAt(now);
        demo.setUpdatedAt(now);
        if (!approve) {
            demo.setRejectionReason(reason);
            demo.setStatus(backFrom(demo));
            event(demo, quotation ? DemoEventType.QUOTATION_REJECTED : DemoEventType.COMPLETION_REJECTED, approver, null, reason);
        } else if (quotation) {
            demo.setStatus(DemoStatus.APPROVED);
            event(demo, DemoEventType.QUOTATION_APPROVED, approver, "Budget " + rupees(demo.getProposedBudget()) + " approved", reason);
        } else {
            BigDecimal total = demo.total();
            BigDecimal difference = total.subtract(demo.getBookedTotal());
            if (difference.signum() != 0) {
                LocalDate today = today();
                postingRepository.save(DemoPosting.builder()
                        .demoId(demo.getId())
                        .kind(correction ? DemoPostingKind.CORRECTION : DemoPostingKind.COMPLETED)
                        .amount(difference)
                        // A first completion is dated the last day of the visit, a correction the day it is approved
                        .occurredOn(correction || demo.endDate().isAfter(today) ? today : demo.endDate())
                        .createdAt(now)
                        .build());
            }
            demo.setBookedTotal(total);
            demo.setStatus(DemoStatus.COMPLETED);
            DemoUnitService.releaseReserved(demo);
            if (!correction) {
                demo.setCompletedAt(now);
            }
            demo.setRejectionReason(null);
            event(demo, DemoEventType.COMPLETION_APPROVED, approver, (correction
                    ? "Correction approved: " + signedRupees(difference) + " booked, " : "Completed: ")
                    + rupees(total) + " in all booked as travel & conveyance", reason);
        }
        demoRepository.save(demo);
        notifyDecision(demo, approver, quotation ? "quotation" : correction ? "correction" : "completion", approve, reason, staffUsers);
        return views.detail(demo, approver);
    }

    /** A Super Admin opens a completed demo for correction; the corrected completion is approved again. */
    @Transactional
    public ClientDemoDTOs.Detail reopen(Long id, ClientDemoDTOs.ReasonRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lock(id);
        requireStatus(demo, "reopened", DemoStatus.COMPLETED);
        String reason = request.getReason().trim();
        demo.setStatus(DemoStatus.REOPENED);
        demo.setReopenReason(reason);
        demo.setUpdatedAt(clock.instant());
        demoRepository.save(demo);
        event(demo, DemoEventType.REOPENED, actor, rupees(demo.getBookedTotal()) + " stays booked until the correction is approved", reason);
        return views.detail(demo, actor);
    }

    /** Calls off a demo that has no bills yet. */
    @Transactional
    public ClientDemoDTOs.Detail cancel(Long id, ClientDemoDTOs.ReasonRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lock(id);
        requireStatus(demo, "cancelled", DemoStatus.DRAFT, DemoStatus.QUOTATION_PENDING, DemoStatus.APPROVED);
        DemoUnitService.requireAllBack(demo, "cancelled");
        if (demo.total().signum() > 0) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_WRONG_STATUS, demo.reference() + " already has " + rupees(demo.total())
                    + " of bills. Send it for completion with the outcome instead, so the money spent is accounted for.");
        }
        String reason = request.getReason().trim();
        demo.setStatus(DemoStatus.CANCELLED);
        demo.setCancelReason(reason);
        DemoUnitService.releaseReserved(demo);
        demo.setUpdatedAt(clock.instant());
        demoRepository.save(demo);
        event(demo, DemoEventType.CANCELLED, actor, null, reason);
        Set<Long> recipients = new LinkedHashSet<>(views.staffUserIds(demo));
        recipients.add(demo.getCreatedBy());
        recipients.remove(actor.getId());
        recipients.forEach(userId -> notificationService.createForUser(userId, "Client demo cancelled",
                describe(demo) + " was cancelled by " + actor.getUsername() + ": " + reason, linkFor(demo, userId)));
        return views.detail(demo, actor);
    }

    // ------------------------------------------------------------------ helpers

    ClientDemo find(Long id) {
        return demoRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    ClientDemo lock(Long id) {
        return demoRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
    }

    /** A demo the caller attends; any other is "not found", so its existence is not revealed. */
    ClientDemo attended(Long id, User caller) {
        ClientDemo demo = find(id);
        if (demo.getStatus() == DemoStatus.DRAFT || !views.staffUserIds(demo).contains(caller.getId())) {
            throw notFound(id);
        }
        return demo;
    }

    void event(ClientDemo demo, DemoEventType type, User actor, String detail, String reason) {
        eventRepository.save(DemoEvent.builder()
                .demoId(demo.getId())
                .type(type)
                .actorId(actor.getId())
                .at(clock.instant())
                .detail(detail == null ? null : truncate(detail, 1000))
                .reason(reason)
                .build());
    }

    Instant now() {
        return clock.instant();
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    static ApiException notEditable(ClientDemo demo) {
        String message = switch (demo.getStatus()) {
            case QUOTATION_PENDING, COMPLETION_PENDING -> demo.reference() + " is waiting for approval. Withdraw it first to change it.";
            case COMPLETED -> demo.reference() + " is completed. Only a Super Admin can reopen it for a correction.";
            case REOPENED -> demo.reference() + " was reopened for a correction, which only a Super Admin makes.";
            case CANCELLED -> demo.reference() + " was cancelled.";
            default -> "You can't change " + demo.reference() + ".";
        };
        return ApiErrors.of(HttpStatus.CONFLICT, ErrorCodes.DEMO_NOT_EDITABLE, message);
    }

    static String rupees(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "₹" + format.format(amount);
    }

    private static String signedRupees(BigDecimal amount) {
        return amount.signum() < 0 ? "-" + rupees(amount.negate()) : "+" + rupees(amount);
    }

    private static String days(int days) {
        return days + (days == 1 ? " day" : " days");
    }

    private static String describe(ClientDemo demo) {
        return demo.reference() + " (" + demo.getDemoType().label() + " at " + clientsLabel(demo) + ", " + demo.getCity() + ", "
                + DAY.format(demo.getStartDate()) + ")";
    }

    // "Hotel Sunrise", or "Hotel Sunrise and 2 more clients"
    private static String clientsLabel(ClientDemo demo) {
        int more = demo.getVisits().size() - 1;
        return demo.getClientName() + (more <= 0 ? "" : " and " + more + (more == 1 ? " more client" : " more clients"));
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Client demo not found with id: " + id);
    }

    private static void requireStatus(ClientDemo demo, String action, DemoStatus... allowed) {
        if (!EnumSet.of(allowed[0], allowed).contains(demo.getStatus())) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_WRONG_STATUS, demo.reference() + " is " + demo.getStatus().label().toLowerCase(Locale.ROOT)
                    + ", so it can't be " + action + ".");
        }
    }

    // Where a rejected or withdrawn request goes back to
    private static DemoStatus backFrom(ClientDemo demo) {
        if (demo.getStatus() == DemoStatus.QUOTATION_PENDING) {
            return DemoStatus.DRAFT;
        }
        return demo.completedBefore() ? DemoStatus.REOPENED : DemoStatus.APPROVED;
    }

    private void sendForApproval(ClientDemo demo, DemoStatus status, User actor, Instant now) {
        demo.setStatus(status);
        demo.setSubmittedBy(actor.getId());
        demo.setSubmittedAt(now);
        demo.setDecidedBy(null);
        demo.setDecidedAt(null);
        demo.setUpdatedAt(now);
    }

    private void notifyApprovers(ClientDemo demo, User actor, String title, String message) {
        notificationService.createForPermission(Permission.DEMO_APPROVE, actor.getId(), title, message, NotificationLinks.APPROVALS);
    }

    private void notifyDecision(ClientDemo demo, User approver, String what, boolean approved, String reason, Set<Long> staffUsers) {
        String title = "Demo " + what + (approved ? " approved" : " rejected");
        String message = describe(demo) + ": the " + what + " was " + (approved ? "approved" : "rejected") + " by "
                + approver.getUsername() + (reason == null ? "." : " (" + reason + ").");
        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(demo.getCreatedBy());
        recipients.add(demo.getSubmittedBy());
        recipients.remove(null);
        recipients.remove(approver.getId());
        recipients.forEach(userId -> notificationService.createForUser(userId, title, message, linkFor(demo, userId)));
        if ("quotation".equals(what) && approved) {
            // The attending staff now record their bills under "My demos"
            staffUsers.stream().filter(userId -> !recipients.contains(userId) && !userId.equals(approver.getId()))
                    .forEach(userId -> notificationService.createForUser(userId, "You are attending a client demo",
                            "You are tagged on " + describe(demo) + " at " + demo.getStartTime() + ". Record your hotel, food and "
                                    + "travel bills on it under My demos.", NotificationLinks.MY_DEMOS));
        }
    }

    // Managers open the demo itself; attending staff their own list
    private String linkFor(ClientDemo demo, Long userId) {
        return userId.equals(demo.getCreatedBy()) || userId.equals(demo.getSubmittedBy())
                ? NotificationLinks.CLIENT_DEMOS + "/" + demo.getId() : NotificationLinks.MY_DEMOS;
    }

    /**
     * Of these employees, the ids of those whose role lets them attend demos (DEMO_VIEW_OWN). Two queries whatever
     * the number: their accounts, then their designations and custom roles.
     */
    private Set<Long> attendingEmployeeIds(Collection<Employee> employees) {
        List<Long> userIds = employees.stream().map(Employee::getUserId).filter(Objects::nonNull).toList();
        if (userIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, UserAccess> access = accessResolver.resolveAll(userRepository.findAllById(userIds));
        return employees.stream()
                .filter(employee -> employee.getUserId() != null)
                .filter(employee -> {
                    UserAccess userAccess = access.get(employee.getUserId());
                    return userAccess != null && userAccess.has(Permission.DEMO_VIEW_OWN);
                })
                .map(Employee::getId)
                .collect(Collectors.toSet());
    }

    /**
     * Copies the request onto the demo and describes what changed ("" when nothing did). Attending staff must be active
     * employees, except those already tagged (who may have left since). Bills beyond the last day are refused by
     * {@link #syncPlan}, which runs next.
     */
    private String apply(ClientDemo demo, ClientDemoDTOs.DemoRequest request, Set<Long> alreadyTagged) {
        Set<Long> staffIds = new LinkedHashSet<>(request.getStaffEmployeeIds() == null ? List.of() : request.getStaffEmployeeIds());
        Map<Long, Employee> employees = employeeRepository.findAllById(staffIds).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        List<Employee> newlyTagged = new ArrayList<>();
        for (Long employeeId : staffIds) {
            Employee employee = employees.get(employeeId);
            if (employee == null) {
                throw ApiErrors.fieldError("staffEmployeeIds", "Employee " + employeeId + " was not found.");
            }
            if (!alreadyTagged.contains(employeeId) && (!TAGGABLE.contains(employee.getStatus()) || employee.getUserId() == null)) {
                throw ApiErrors.fieldError("staffEmployeeIds", employee.getDisplayName() + " is not an active employee.");
            }
            if (!alreadyTagged.contains(employeeId)) {
                newlyTagged.add(employee);
            }
        }
        // Only staff whose role goes on demos; those already tagged stay even if their role changed since
        Set<Long> attending = attendingEmployeeIds(newlyTagged);
        newlyTagged.stream().filter(employee -> !attending.contains(employee.getId())).findFirst().ifPresent(employee -> {
            throw ApiErrors.fieldError("staffEmployeeIds", employee.getDisplayName() + "'s role does not go on client demos, "
                    + "so they can't be tagged as attending staff.");
        });

        List<String> changes = new ArrayList<>();
        boolean isNew = demo.getId() == null;
        change(changes, isNew, "type", demo.getDemoType(), request.getDemoType(), demo::setDemoType, DemoType::label);
        LocalDate oldStart = demo.getStartDate();
        change(changes, isNew, "date", demo.getStartDate(), request.getStartDate(), demo::setStartDate, DAY::format);
        if (oldStart != null && !oldStart.equals(demo.getStartDate())) {
            // Dated stays and journeys move with the trip
            long shift = java.time.temporal.ChronoUnit.DAYS.between(oldStart, demo.getStartDate());
            demo.getExpenses().stream().filter(expense -> expense.getStartDate() != null).forEach(expense -> {
                expense.setStartDate(expense.getStartDate().plusDays(shift));
                expense.setEndDate(expense.getEndDate().plusDays(shift));
            });
        }
        change(changes, isNew, "days", demo.getNumberOfDays(), request.getNumberOfDays(), demo::setNumberOfDays, String::valueOf);
        applyVisits(demo, request.getClients(), changes, isNew);
        applyProducts(demo, request.getProducts(), changes, isNew);
        BigDecimal budget = request.getProposedBudget().setScale(2, java.math.RoundingMode.HALF_UP);
        if (demo.getProposedBudget() == null || demo.getProposedBudget().compareTo(budget) != 0) {
            if (!isNew) {
                changes.add("budget " + rupees(demo.getProposedBudget()) + " → " + rupees(budget));
            }
            demo.setProposedBudget(budget);
        }
        String purpose = OptionalText.blankToNull(request.getPurpose());
        if (!Objects.equals(demo.getPurpose(), purpose)) {
            if (!isNew) {
                changes.add("purpose changed");
            }
            demo.setPurpose(purpose);
        }
        if (!demo.getStaffEmployeeIds().equals(staffIds)) {
            if (!isNew) {
                List<String> added = staffIds.stream().filter(staffId -> !demo.getStaffEmployeeIds().contains(staffId))
                        .map(staffId -> employees.get(staffId).getDisplayName()).toList();
                List<Long> removedIds = demo.getStaffEmployeeIds().stream().filter(staffId -> !staffIds.contains(staffId)).toList();
                Map<Long, String> removedNames = employeeRepository.findAllById(removedIds).stream()
                        .collect(Collectors.toMap(Employee::getId, Employee::getDisplayName));
                List<String> removed = removedIds.stream().map(staffId -> removedNames.getOrDefault(staffId, "#" + staffId)).toList();
                if (!added.isEmpty()) changes.add("staff added: " + String.join(", ", added));
                if (!removed.isEmpty()) changes.add("staff removed: " + String.join(", ", removed));
            }
            demo.getStaffEmployeeIds().clear();
            demo.getStaffEmployeeIds().addAll(staffIds);
        }
        return String.join("; ", changes);
    }

    /**
     * Checks the clients visited (each within the trip, ending after it starts, never overlapping another visit that
     * day, and never the same client twice in a row on a day) and replaces the demo's visits when they changed. The
     * demo's own client columns follow the first visit.
     */
    private void applyVisits(ClientDemo demo, List<ClientDemoDTOs.VisitRequest> requests, List<String> changes, boolean isNew) {
        List<DemoVisit> visits = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            visits.add(visit(demo, requests.get(index), "clients[" + index + "]"));
        }
        List<DemoVisit> byTime = visits.stream()
                .sorted(Comparator.comparing(DemoVisit::getDayNumber).thenComparing(DemoVisit::getStartTime)).toList();
        for (int index = 1; index < byTime.size(); index++) {
            DemoVisit before = byTime.get(index - 1);
            DemoVisit after = byTime.get(index);
            if (before.getDayNumber().equals(after.getDayNumber()) && before.getEndTime().isAfter(after.getStartTime())) {
                throw ApiErrors.fieldError("clients", "The visits to " + before.getClientName() + " (" + before.getStartTime() + "–"
                        + before.getEndTime() + ") and " + after.getClientName() + " (" + after.getStartTime() + "–" + after.getEndTime()
                        + ") overlap on day " + after.getDayNumber() + ". Give each client its own time.");
            }
            if (before.getDayNumber().equals(after.getDayNumber()) && before.sameClientAs(after)) {
                // The same client (by its own phone or email) twice in a row is one longer visit
                throw ApiErrors.fieldError("clients", after.getClientName() + " is visited twice in a row on day " + after.getDayNumber()
                        + " (" + before.getStartTime() + "–" + before.getEndTime() + " and " + after.getStartTime() + "–" + after.getEndTime()
                        + "; the same client phone or email). Make it one visit from " + before.getStartTime() + " to " + after.getEndTime()
                        + ", or visit another client in between.");
            }
        }
        List<List<Object>> current = demo.getVisits().stream().map(ClientDemoService::fields).toList();
        List<List<Object>> requested = byTime.stream().map(ClientDemoService::fields).toList();
        if (current.equals(requested)) {
            return;
        }
        if (!isNew) {
            changes.add("clients " + visitsLabel(demo.getVisits()) + " → " + visitsLabel(byTime));
        }
        demo.getVisits().clear();
        demo.getVisits().addAll(byTime);
        demo.mirrorFirstVisit();
    }

    private static DemoVisit visit(ClientDemo demo, ClientDemoDTOs.VisitRequest request, String field) {
        String categoryOther = OptionalText.blankToNull(request.getClientCategoryOther());
        if (request.getClientCategory() == ClientCategory.OTHER && categoryOther == null) {
            throw ApiErrors.fieldError(field + ".clientCategoryOther", "Say what kind of place " + request.getClientName().trim() + " is.");
        }
        if (request.getDayNumber() > demo.getNumberOfDays()) {
            throw ApiErrors.fieldError(field + ".dayNumber", "The visit to " + request.getClientName().trim() + " is on day "
                    + request.getDayNumber() + ", but the demo is " + days(demo.getNumberOfDays()) + " long.");
        }
        LocalTime start = request.getStartTime().withSecond(0).withNano(0);
        LocalTime end = request.getEndTime().withSecond(0).withNano(0);
        if (!end.isAfter(start)) {
            throw ApiErrors.fieldError(field + ".endTime", "The visit to " + request.getClientName().trim() + " must end after it starts.");
        }
        boolean business = request.getClientCategory().hasLegalDetails();
        return DemoVisit.builder()
                .demo(demo)
                .clientCategory(request.getClientCategory())
                .clientCategoryOther(request.getClientCategory() == ClientCategory.OTHER ? categoryOther : null)
                .clientName(request.getClientName().trim())
                .legalName(business ? OptionalText.blankToNull(request.getLegalName()) : null)
                .gstin(business ? OptionalText.blankToNull(request.getGstin()) : null)
                .clientPhone(request.getClientPhone().trim())
                .clientEmail(OptionalText.lowerCaseOrNull(request.getClientEmail()))
                .contactPerson(OptionalText.blankToNull(request.getContactPerson()))
                .contactPhone(request.getContactPhone().trim())
                .contactEmail(OptionalText.lowerCaseOrNull(request.getContactEmail()))
                .addressLine(request.getAddressLine().trim())
                .city(request.getCity().trim())
                .state(OptionalText.blankToNull(request.getState()))
                .pincode(OptionalText.blankToNull(request.getPincode()))
                .mapLink(OptionalText.blankToNull(request.getMapLink()))
                .dayNumber(request.getDayNumber())
                .startTime(start)
                .endTime(end)
                .build();
    }

    // Everything a visit says, to tell whether the plan changed
    private static List<Object> fields(DemoVisit visit) {
        return java.util.Arrays.asList(visit.getClientCategory(), visit.getClientCategoryOther(), visit.getClientName(),
                visit.getLegalName(), visit.getGstin(), visit.getClientPhone(), visit.getClientEmail(), visit.getContactPerson(), visit.getContactPhone(), visit.getContactEmail(),
                visit.getAddressLine(), visit.getCity(), visit.getState(), visit.getPincode(), visit.getMapLink(),
                visit.getDayNumber(), visit.getStartTime(), visit.getEndTime());
    }

    private static String visitsLabel(List<DemoVisit> visits) {
        return visits.isEmpty() ? "—" : visits.stream().map(DemoVisit::describe).collect(Collectors.joining(", "));
    }

    /**
     * The products shown, each once, with the units taken by barcode (see {@link DemoUnitPlanner#applyPlan}); replaced
     * when they changed.
     */
    private void applyProducts(ClientDemo demo, List<ClientDemoDTOs.ProductRequest> sent, List<String> changes, boolean isNew) {
        List<ClientDemoDTOs.ProductRequest> requests = sent == null ? List.of() : sent;
        Set<Long> seen = new java.util.HashSet<>();
        for (ClientDemoDTOs.ProductRequest request : requests) {
            if (!seen.add(request.getProductId())) {
                throw ApiErrors.fieldError("products", request.getProductName().trim() + " is listed twice. Pick more of its units instead.");
            }
        }
        Map<Long, Integer> quantities = unitPlanner.applyPlan(demo, requests, changes, isNew);
        Map<Long, DemoProduct> byId = new java.util.LinkedHashMap<>();
        for (ClientDemoDTOs.ProductRequest request : requests) {
            byId.put(request.getProductId(), new DemoProduct(request.getProductId(), request.getProductName().trim(),
                    quantities.get(request.getProductId())));
        }
        List<DemoProduct> requested = byId.values().stream().sorted(Comparator.comparing(DemoProduct::getProductName)).toList();
        if (Set.copyOf(demo.getProducts()).equals(Set.copyOf(requested))) {
            return;
        }
        if (!isNew) {
            changes.add("products " + productsLabel(demo.getProducts()) + " → " + productsLabel(requested));
        }
        demo.getProducts().clear();
        demo.getProducts().addAll(requested);
    }

    private static String productsLabel(List<DemoProduct> products) {
        return products.isEmpty() ? "—" : products.stream().map(product -> product.getProductName() + " ×" + product.getQuantity())
                .collect(Collectors.joining(", "));
    }

    /**
     * Makes the demo's stay, meal and travel bills match the day-by-day plan (nothing when no plan was sent): listed
     * bills with an id are changed, new ones added in the order given, unlisted stays, meals and travel removed. Other
     * bills are kept. Returns the timeline entries, saved once the demo is.
     */
    private List<PlanEvent> syncPlan(ClientDemo demo, List<ClientDemoDTOs.ExpenseRequest> plan, User actor, String reason, Instant now) {
        List<PlanEvent> events = new ArrayList<>();
        if (plan != null) {
            Map<Long, DemoExpense> planned = demo.getExpenses().stream().filter(expense -> PLAN_KINDS.contains(expense.getKind()))
                    .collect(Collectors.toMap(DemoExpense::getId, Function.identity()));
            Set<Long> kept = new java.util.HashSet<>();
            for (ClientDemoDTOs.ExpenseRequest bill : plan) {
                if (!PLAN_KINDS.contains(bill.getKind())) {
                    throw ApiErrors.fieldError("plannedBills", "The day-by-day plan holds stays, meals and travel. Add other bills on the demo.");
                }
                if (bill.getId() == null) {
                    DemoExpense expense = DemoExpense.builder().demo(demo).addedBy(actor.getId()).addedAt(now).build();
                    DemoBills.apply(demo, expense, bill);
                    demo.getExpenses().add(expense);
                    events.add(new PlanEvent(DemoEventType.EXPENSE_ADDED, DemoBills.describe(expense), null));
                    continue;
                }
                DemoExpense expense = planned.get(bill.getId());
                if (expense == null || !kept.add(bill.getId())) {
                    throw ApiErrors.fieldError("plannedBills", "Bill " + bill.getId() + " is not one of this demo's stays, meals or travel.");
                }
                List<Object> before = fields(expense);
                String described = DemoBills.describe(expense);
                DemoBills.apply(demo, expense, bill);
                if (!before.equals(fields(expense))) {
                    expense.setUpdatedBy(actor.getId());
                    expense.setUpdatedAt(now);
                    String after = DemoBills.describe(expense);
                    events.add(new PlanEvent(DemoEventType.EXPENSE_UPDATED, described.equals(after) ? after : described + " → " + after, reason));
                }
            }
            List<DemoExpense> dropped = planned.values().stream().filter(expense -> !kept.contains(expense.getId()))
                    .sorted(Comparator.comparing(DemoExpense::getId)).toList();
            dropped.forEach(expense -> events.add(new PlanEvent(DemoEventType.EXPENSE_REMOVED, DemoBills.describe(expense), reason)));
            demo.getExpenses().removeAll(dropped);
            if (demo.getExpenses().size() > ClientDemoDTOs.MAX_EXPENSES) {
                throw ApiErrors.fieldError("plannedBills", "A demo holds at most " + ClientDemoDTOs.MAX_EXPENSES + " bills.");
            }
        }
        int lastBillDay = demo.getExpenses().stream().map(DemoExpense::getDayNumber).filter(Objects::nonNull)
                .max(Integer::compare).orElse(0);
        if (demo.getNumberOfDays() < lastBillDay) {
            throw ApiErrors.fieldError("numberOfDays", "There are bills for day " + lastBillDay + ". Move or remove them before "
                    + "shortening the visit to " + days(demo.getNumberOfDays()) + ".");
        }
        return events;
    }

    /**
     * The proposed budget is the day-by-day plan's stays, meals and travel plus an extra amount, which needs its reason
     * and breakup. Keeps the extra and its reason on the demo; returns what changed on an existing demo ("" if nothing).
     */
    private static String applyExtraBudget(ClientDemo demo, String requestedReason, boolean isNew) {
        BigDecimal planned = demo.getExpenses().stream().filter(expense -> PLAN_KINDS.contains(expense.getKind()))
                .map(DemoExpense::getAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal extra = demo.getProposedBudget().subtract(planned);
        if (extra.signum() < 0) {
            throw ApiErrors.fieldError("proposedBudget", "The proposed budget (" + rupees(demo.getProposedBudget()) + ") can't be less "
                    + "than the day-by-day plan's stays, meals and travel (" + rupees(planned) + ").");
        }
        String reason = extra.signum() == 0 ? null : OptionalText.blankToNull(requestedReason);
        if (extra.signum() > 0 && reason == null) {
            throw ApiErrors.fieldError("extraBudgetReason", "The budget is " + rupees(extra) + " more than the day-by-day plan ("
                    + rupees(planned) + "). Say why the extra is needed, with its breakup.");
        }
        boolean reasonChanged = !Objects.equals(demo.getExtraBudgetReason(), reason);
        demo.setExtraBudget(extra);
        demo.setExtraBudgetReason(reason);
        if (isNew || !reasonChanged) {
            return "";
        }
        return reason == null ? "no extra budget" : "extra budget " + rupees(extra) + ": " + reason;
    }

    // Everything a bill says, to tell whether the plan changed it
    private static List<Object> fields(DemoExpense expense) {
        return java.util.Arrays.asList(expense.getKind(), expense.getDayNumber(), expense.getVendorName(), expense.getVendorAddress(),
                expense.getVendorMapLink(), expense.getVendorPhone(), expense.getVendorGstin(), expense.getMealType(),
                expense.getFromPlace(), expense.getToPlace(), expense.getTravelMode(), expense.getStartDate(), expense.getStartTime(),
                expense.getEndDate(), expense.getEndTime(), expense.getReferenceNumber(), expense.getAmount(),
                expense.getGstAmount(), expense.getDescription());
    }

    // A timeline entry of the plan, written once the demo has its id
    private record PlanEvent(DemoEventType type, String detail, String reason) {
    }

    private void saveEvents(ClientDemo demo, List<PlanEvent> events, User actor, Instant now) {
        eventRepository.saveAll(events.stream().map(event -> DemoEvent.builder()
                .demoId(demo.getId())
                .type(event.type())
                .actorId(actor.getId())
                .at(now)
                .detail(truncate(event.detail(), 1000))
                .reason(event.reason())
                .build()).toList());
    }

    private static <T> void change(List<String> changes, boolean isNew, String field, T current, T requested,
                                   java.util.function.Consumer<T> setter, Function<T, String> label) {
        if (Objects.equals(current, requested)) {
            return;
        }
        if (!isNew) {
            changes.add(field + " " + (current == null ? "—" : label.apply(current)) + " → " + (requested == null ? "—" : label.apply(requested)));
        }
        setter.accept(requested);
    }
}
