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
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
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

    private final ClientDemoRepository demoRepository;
    private final DemoEventRepository eventRepository;
    private final DemoPostingRepository postingRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final NotificationService notificationService;
    private final DemoViews views;
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

    /** Employees who can be tagged as attending staff. */
    @Transactional(readOnly = true)
    public List<ClientDemoDTOs.StaffMember> staffOptions() {
        return employeeRepository.findAll().stream()
                .filter(employee -> TAGGABLE.contains(employee.getStatus()) && employee.getUserId() != null)
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
        demo.setUpdatedAt(now);
        demo = demoRepository.save(demo);
        event(demo, DemoEventType.CREATED, actor, "Planned " + demo.getDemoType().label().toLowerCase(Locale.ROOT) + " at "
                + demo.getClientName() + " on " + DAY.format(demo.getStartDate()), null);
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
        String changes = apply(demo, request, Set.copyOf(demo.getStaffEmployeeIds()));
        if (changes.isEmpty()) {
            return views.detail(demo, actor);
        }
        Instant now = clock.instant();
        demo.setUpdatedAt(now);
        boolean needsApproval = demo.getStatus() == DemoStatus.APPROVED
                && (oldBudget.compareTo(demo.getProposedBudget()) != 0 || oldDays != demo.getNumberOfDays());
        event(demo, DemoEventType.UPDATED, actor, changes, reason);
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
        if (demo.total().signum() > 0) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_WRONG_STATUS, demo.reference() + " already has " + rupees(demo.total())
                    + " of bills. Send it for completion with the outcome instead, so the money spent is accounted for.");
        }
        String reason = request.getReason().trim();
        demo.setStatus(DemoStatus.CANCELLED);
        demo.setCancelReason(reason);
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
        return demo.reference() + " (" + demo.getDemoType().label() + " at " + demo.getClientName() + ", " + demo.getCity() + ", "
                + DAY.format(demo.getStartDate()) + ")";
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
     * Copies the request onto the demo and describes what changed ("" when nothing did). Attending staff must be active
     * employees, except those already tagged (who may have left since).
     */
    private String apply(ClientDemo demo, ClientDemoDTOs.DemoRequest request, Set<Long> alreadyTagged) {
        String categoryOther = OptionalText.blankToNull(request.getClientCategoryOther());
        if (request.getClientCategory() == ClientCategory.OTHER && categoryOther == null) {
            throw ApiErrors.fieldError("clientCategoryOther", "Say what kind of place the client is.");
        }
        if (request.getClientCategory() != ClientCategory.OTHER) {
            categoryOther = null;
        }
        int lastBillDay = demo.getExpenses().stream().map(DemoExpense::getDayNumber).filter(Objects::nonNull)
                .max(Integer::compare).orElse(0);
        if (request.getNumberOfDays() < lastBillDay) {
            throw ApiErrors.fieldError("numberOfDays", "There are bills for day " + lastBillDay + ". Move or remove them before "
                    + "shortening the visit to " + days(request.getNumberOfDays()) + ".");
        }

        Set<Long> staffIds = new LinkedHashSet<>(request.getStaffEmployeeIds() == null ? List.of() : request.getStaffEmployeeIds());
        Map<Long, Employee> employees = employeeRepository.findAllById(staffIds).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        for (Long employeeId : staffIds) {
            Employee employee = employees.get(employeeId);
            if (employee == null) {
                throw ApiErrors.fieldError("staffEmployeeIds", "Employee " + employeeId + " was not found.");
            }
            if (!alreadyTagged.contains(employeeId) && (!TAGGABLE.contains(employee.getStatus()) || employee.getUserId() == null)) {
                throw ApiErrors.fieldError("staffEmployeeIds", employee.getDisplayName() + " is not an active employee.");
            }
        }

        List<String> changes = new ArrayList<>();
        boolean isNew = demo.getId() == null;
        change(changes, isNew, "type", demo.getDemoType(), request.getDemoType(), demo::setDemoType, DemoType::label);
        change(changes, isNew, "client category", demo.getClientCategory(), request.getClientCategory(), demo::setClientCategory, ClientCategory::label);
        change(changes, isNew, "client category", demo.getClientCategoryOther(), categoryOther, demo::setClientCategoryOther, Function.identity());
        change(changes, isNew, "client name", demo.getClientName(), request.getClientName().trim(), demo::setClientName, Function.identity());
        change(changes, isNew, "contact person", demo.getContactPerson(), OptionalText.blankToNull(request.getContactPerson()), demo::setContactPerson, Function.identity());
        change(changes, isNew, "contact phone", demo.getContactPhone(), request.getContactPhone().trim(), demo::setContactPhone, Function.identity());
        change(changes, isNew, "contact email", demo.getContactEmail(), OptionalText.lowerCaseOrNull(request.getContactEmail()), demo::setContactEmail, Function.identity());
        change(changes, isNew, "address", demo.getAddressLine(), request.getAddressLine().trim(), demo::setAddressLine, Function.identity());
        change(changes, isNew, "city", demo.getCity(), request.getCity().trim(), demo::setCity, Function.identity());
        change(changes, isNew, "state", demo.getState(), OptionalText.blankToNull(request.getState()), demo::setState, Function.identity());
        change(changes, isNew, "PIN code", demo.getPincode(), OptionalText.blankToNull(request.getPincode()), demo::setPincode, Function.identity());
        change(changes, isNew, "location link", demo.getMapLink(), OptionalText.blankToNull(request.getMapLink()), demo::setMapLink, Function.identity());
        change(changes, isNew, "date", demo.getStartDate(), request.getStartDate(), demo::setStartDate, DAY::format);
        change(changes, isNew, "time", demo.getStartTime(), request.getStartTime().withSecond(0).withNano(0), demo::setStartTime, Object::toString);
        change(changes, isNew, "days", demo.getNumberOfDays(), request.getNumberOfDays(), demo::setNumberOfDays, String::valueOf);
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
                List<String> removed = demo.getStaffEmployeeIds().stream().filter(staffId -> !staffIds.contains(staffId))
                        .map(staffId -> employeeRepository.findById(staffId).map(Employee::getDisplayName).orElse("#" + staffId)).toList();
                if (!added.isEmpty()) changes.add("staff added: " + String.join(", ", added));
                if (!removed.isEmpty()) changes.add("staff removed: " + String.join(", ", removed));
            }
            demo.getStaffEmployeeIds().clear();
            demo.getStaffEmployeeIds().addAll(staffIds);
        }
        return String.join("; ", changes);
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
