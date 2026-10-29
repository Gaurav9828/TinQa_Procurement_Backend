package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.document.entity.Document;
import com.tinqa.procurement.document.repository.DocumentRepository;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Who may do what on a demo, and how a demo is shown to the caller. Shared by the plan / workflow and the bills
 * services so both answer the same.
 */
@Component
@RequiredArgsConstructor
class DemoViews {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final DemoEventRepository eventRepository;
    private final CurrentAccess currentAccess;

    // ------------------------------------------------------------------ rules

    /** The user accounts of the demo's attending staff. */
    Set<Long> staffUserIds(ClientDemo demo) {
        if (demo.getStaffEmployeeIds().isEmpty()) {
            return Set.of();
        }
        return employeeRepository.findAllById(demo.getStaffEmployeeIds()).stream()
                .map(Employee::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * Whether the caller may approve or reject what the demo is waiting for: an approver who neither made it, sent it,
     * nor attends it (maker-checker).
     */
    boolean decidable(ClientDemo demo, Long userId, Set<Long> staffUserIds) {
        return (demo.getStatus() == DemoStatus.QUOTATION_PENDING || demo.getStatus() == DemoStatus.COMPLETION_PENDING)
                && currentAccess.has(Permission.DEMO_APPROVE)
                && !userId.equals(demo.getCreatedBy())
                && !userId.equals(demo.getSubmittedBy())
                && !staffUserIds.contains(userId);
    }

    /**
     * Whether the caller may change the plan and every bill: demo managers while it is planned or running, only a
     * Super Admin (DEMO_COMPLETED_EDIT) once it was reopened after completion.
     */
    boolean managesBills(ClientDemo demo) {
        if (demo.getStatus() == DemoStatus.REOPENED) {
            return currentAccess.has(Permission.DEMO_COMPLETED_EDIT);
        }
        return demo.getStatus().billsOpen() && currentAccess.has(Permission.DEMO_MANAGE);
    }

    /** Whether attending staff may record or change their own bills: only while the approved demo runs. */
    boolean recordsOwnBill(ClientDemo demo, DemoExpense expense, Long userId, Set<Long> staffUserIds) {
        return demo.getStatus() == DemoStatus.APPROVED
                && currentAccess.has(Permission.DEMO_EXPENSE_RECORD_OWN)
                && staffUserIds.contains(userId)
                && (expense == null || userId.equals(expense.getAddedBy()));
    }

    /**
     * Whether the caller may hand the demo's units out or back now: while the approved demo runs, demo managers, and
     * attending staff on their own demo (DEMO_UNITS_HANDLE_OWN).
     */
    boolean handlesUnits(ClientDemo demo, Long userId, Set<Long> staffUserIds) {
        if (demo.getStatus() != DemoStatus.APPROVED || demo.getUnits().isEmpty()) {
            return false;
        }
        return currentAccess.has(Permission.DEMO_MANAGE)
                || currentAccess.has(Permission.DEMO_UNITS_HANDLE_OWN) && staffUserIds.contains(userId);
    }

    // ------------------------------------------------------------------ views

    /** Display names of user accounts: the employee's name, else the username. */
    Map<Long, String> names(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        userRepository.findAllById(ids).forEach(user -> names.put(user.getId(), user.getUsername()));
        employeeRepository.findByUserIdIn(ids).forEach(employee -> {
            if (employee.getDisplayName() != null) {
                names.put(employee.getUserId(), employee.getDisplayName());
            }
        });
        return names;
    }

    List<ClientDemoDTOs.Summary> summaries(List<ClientDemo> demos, Long userId) {
        Set<Long> employeeIds = demos.stream().flatMap(demo -> demo.getStaffEmployeeIds().stream()).collect(Collectors.toSet());
        Map<Long, Employee> employees = employeeRepository.findAllById(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        Map<Long, String> names = names(demos.stream().map(ClientDemo::getCreatedBy).toList());
        return demos.stream().map(demo -> {
            List<Employee> staff = demo.getStaffEmployeeIds().stream().map(employees::get).filter(Objects::nonNull).toList();
            Set<Long> staffUsers = staff.stream().map(Employee::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
            return ClientDemoDTOs.Summary.builder()
                    .id(demo.getId())
                    .reference(demo.reference())
                    .demoType(demo.getDemoType())
                    .status(demo.getStatus())
                    .statusLabel(demo.getStatus().label())
                    .clientCategory(demo.getClientCategory())
                    .clientCategoryLabel(categoryLabel(demo))
                    .clientName(demo.getClientName())
                    .city(demo.getCity())
                    .clientCount(demo.getVisits().size())
                    .startDate(demo.getStartDate())
                    .startTime(demo.getStartTime())
                    .numberOfDays(demo.getNumberOfDays())
                    .endDate(demo.endDate())
                    .proposedBudget(demo.getProposedBudget())
                    .total(demo.total())
                    .bookedTotal(demo.getBookedTotal())
                    .staffNames(staff.stream().map(Employee::getDisplayName).toList())
                    .createdByName(names.get(demo.getCreatedBy()))
                    .submittedAt(demo.getSubmittedAt())
                    .decidableByYou(decidable(demo, userId, staffUsers))
                    .build();
        }).toList();
    }

    ClientDemoDTOs.Detail detail(ClientDemo demo, User caller) {
        Long userId = caller.getId();
        List<Employee> staff = employeeRepository.findAllById(demo.getStaffEmployeeIds());
        Set<Long> staffUsers = staff.stream().map(Employee::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<DemoEvent> events = eventRepository.findByDemoIdOrderByIdAsc(demo.getId());

        Set<Long> people = new HashSet<>();
        Stream.of(demo.getCreatedBy(), demo.getSubmittedBy(), demo.getDecidedBy()).forEach(people::add);
        demo.getExpenses().forEach(expense -> {
            people.add(expense.getAddedBy());
            people.add(expense.getUpdatedBy());
        });
        events.forEach(event -> people.add(event.getActorId()));
        demo.getUnits().forEach(unit -> {
            people.add(unit.getHandedOutBy());
            people.add(unit.getReturnedBy());
        });
        Map<Long, String> names = names(people);
        Map<Long, String> files = documentRepository.findAllById(demo.getExpenses().stream().map(DemoExpense::getDocumentId)
                        .filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Document::getId, Document::getOriginalFileName));

        boolean managesBills = managesBills(demo);
        Map<ExpenseKind, BigDecimal> totals = new EnumMap<>(ExpenseKind.class);
        for (ExpenseKind kind : ExpenseKind.values()) {
            totals.put(kind, ZERO);
        }
        demo.getExpenses().forEach(expense -> totals.merge(expense.getKind(), expense.getAmount(), BigDecimal::add));
        BigDecimal total = demo.total();

        return ClientDemoDTOs.Detail.builder()
                .id(demo.getId())
                .reference(demo.reference())
                .demoType(demo.getDemoType())
                .status(demo.getStatus())
                .statusLabel(demo.getStatus().label())
                .clientCategory(demo.getClientCategory())
                .clientCategoryLabel(categoryLabel(demo))
                .clientCategoryOther(demo.getClientCategoryOther())
                .clientName(demo.getClientName())
                .contactPerson(demo.getContactPerson())
                .contactPhone(demo.getContactPhone())
                .contactEmail(demo.getContactEmail())
                .addressLine(demo.getAddressLine())
                .city(demo.getCity())
                .state(demo.getState())
                .pincode(demo.getPincode())
                .mapLink(demo.getMapLink())
                .startDate(demo.getStartDate())
                .startTime(demo.getStartTime())
                .numberOfDays(demo.getNumberOfDays())
                .endDate(demo.endDate())
                .proposedBudget(demo.getProposedBudget())
                .extraBudget(demo.getExtraBudget())
                .extraBudgetReason(demo.getExtraBudgetReason())
                .purpose(demo.getPurpose())
                .outcome(demo.getOutcome())
                .clients(demo.getVisits().stream().map(visit -> visit(demo, visit)).toList())
                .products(demo.getProducts().stream()
                        .map(product -> new ClientDemoDTOs.ProductLine(product.getProductId(), product.getProductName(), product.getQuantity(),
                                demo.getUnits().stream().filter(unit -> unit.getProductId().equals(product.getProductId()))
                                        .map(DemoUnit::getCode).toList()))
                        .toList())
                .units(demo.getUnits().stream().map(unit -> unit(demo, unit, names)).toList())
                .handlesUnits(handlesUnits(demo, userId, staffUsers))
                .writesOffUnits(demo.getStatus() == DemoStatus.APPROVED && currentAccess.has(Permission.DEMO_UNIT_WRITE_OFF)
                        && demo.getUnits().stream().anyMatch(unit -> unit.getStatus() == DemoUnitStatus.OUT))
                .staff(staff.stream().map(DemoViews::staffMember)
                        .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(nullToEmpty(a.getName()), nullToEmpty(b.getName())))
                        .toList())
                .expenses(demo.getExpenses().stream().map(expense -> expense(demo, expense, names, files,
                        managesBills || recordsOwnBill(demo, expense, userId, staffUsers))).toList())
                .totalsByKind(totals)
                .total(total)
                .budgetLeft(demo.getProposedBudget().subtract(total))
                .bookedTotal(demo.getBookedTotal())
                .createdBy(demo.getCreatedBy())
                .createdByName(names.get(demo.getCreatedBy()))
                .createdAt(demo.getCreatedAt())
                .submittedByName(names.get(demo.getSubmittedBy()))
                .submittedAt(demo.getSubmittedAt())
                .decidedByName(names.get(demo.getDecidedBy()))
                .decidedAt(demo.getDecidedAt())
                .rejectionReason(demo.getRejectionReason())
                .cancelReason(demo.getCancelReason())
                .reopenReason(demo.getReopenReason())
                .completedAt(demo.getCompletedAt())
                .events(events.stream().map(event -> ClientDemoDTOs.Event.builder()
                        .type(event.getType())
                        .actorName(names.get(event.getActorId()))
                        .at(event.getAt())
                        .detail(event.getDetail())
                        .reason(event.getReason())
                        .build()).toList())
                .decidableByYou(decidable(demo, userId, staffUsers))
                .attendingYou(staffUsers.contains(userId))
                .build();
    }

    private static ClientDemoDTOs.Unit unit(ClientDemo demo, DemoUnit unit, Map<Long, String> names) {
        String productName = demo.getProducts().stream().filter(product -> product.getProductId().equals(unit.getProductId()))
                .map(DemoProduct::getProductName).findFirst().orElse(null);
        return ClientDemoDTOs.Unit.builder()
                .barcode(unit.getCode())
                .productId(unit.getProductId())
                .productName(productName)
                .status(unit.getStatus())
                .statusLabel(unit.getStatus().label())
                .handedOutAt(unit.getHandedOutAt())
                .handedOutByName(names.get(unit.getHandedOutBy()))
                .returnedAt(unit.getReturnedAt())
                .returnedByName(names.get(unit.getReturnedBy()))
                .writeOffReason(unit.getWriteOffReason())
                .build();
    }

    static ClientDemoDTOs.StaffMember staffMember(Employee employee) {
        return ClientDemoDTOs.StaffMember.builder()
                .employeeId(employee.getId())
                .employeeCode(employee.getEmployeeCode())
                .name(employee.getDisplayName())
                .designation(employee.getDesignation() != null ? employee.getDesignation().label() : null)
                .build();
    }

    static String categoryLabel(ClientDemo demo) {
        return demo.getClientCategory() == ClientCategory.OTHER && demo.getClientCategoryOther() != null
                ? demo.getClientCategoryOther() : demo.getClientCategory().label();
    }

    private static ClientDemoDTOs.Visit visit(ClientDemo demo, DemoVisit visit) {
        return ClientDemoDTOs.Visit.builder()
                .id(visit.getId())
                .clientCategory(visit.getClientCategory())
                .clientCategoryLabel(visit.getClientCategory() == ClientCategory.OTHER && visit.getClientCategoryOther() != null
                        ? visit.getClientCategoryOther() : visit.getClientCategory().label())
                .clientCategoryOther(visit.getClientCategoryOther())
                .clientName(visit.getClientName())
                .legalName(visit.getLegalName())
                .gstin(visit.getGstin())
                .clientPhone(visit.getClientPhone())
                .clientEmail(visit.getClientEmail())
                .contactPerson(visit.getContactPerson())
                .contactPhone(visit.getContactPhone())
                .contactEmail(visit.getContactEmail())
                .addressLine(visit.getAddressLine())
                .city(visit.getCity())
                .state(visit.getState())
                .pincode(visit.getPincode())
                .mapLink(visit.getMapLink())
                .dayNumber(visit.getDayNumber())
                .date(demo.getStartDate().plusDays(visit.getDayNumber() - 1L))
                .startTime(visit.getStartTime())
                .endTime(visit.getEndTime())
                .build();
    }

    private static ClientDemoDTOs.Expense expense(ClientDemo demo, DemoExpense expense, Map<Long, String> names,
                                                  Map<Long, String> files, boolean editable) {
        return ClientDemoDTOs.Expense.builder()
                .id(expense.getId())
                .kind(expense.getKind())
                .dayNumber(expense.getDayNumber())
                .date(expense.getDayNumber() == null ? null : demo.getStartDate().plusDays(expense.getDayNumber() - 1L))
                .vendorName(expense.getVendorName())
                .vendorAddress(expense.getVendorAddress())
                .vendorMapLink(expense.getVendorMapLink())
                .vendorPhone(expense.getVendorPhone())
                .vendorGstin(expense.getVendorGstin())
                .mealType(expense.getMealType())
                .fromPlace(expense.getFromPlace())
                .toPlace(expense.getToPlace())
                .travelMode(expense.getTravelMode())
                .startDate(expense.getStartDate())
                .startTime(expense.getStartTime())
                .endDate(expense.getEndDate())
                .endTime(expense.getEndTime())
                .referenceNumber(expense.getReferenceNumber())
                .amount(expense.getAmount())
                .gstAmount(expense.getGstAmount())
                .description(expense.getDescription())
                .reason(expense.getReason())
                .invoiceFileName(expense.getDocumentId() == null ? null : files.get(expense.getDocumentId()))
                .addedBy(expense.getAddedBy())
                .addedByName(names.get(expense.getAddedBy()))
                .addedAt(expense.getAddedAt())
                .updatedByName(names.get(expense.getUpdatedBy()))
                .updatedAt(expense.getUpdatedAt())
                .editableByYou(editable)
                .build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
