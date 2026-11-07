package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.supplier.login.SupplierLoginService;
import com.tinqa.procurement.finance.service.PurchasePostingRecorder;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.order.tax.QuotationTax;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Payments TinQa makes to a supplier for an order, against the quotation's payment plan, and the refunds the supplier
 * owes back.
 *
 * <ul>
 *     <li>The advance can be paid once the order is approved, the midterm once the supplier has shipped it. Until the
 *     delivered units are tested, at most the advance and midterm parts of the quoted total can be paid: the final
 *     part is held back.</li>
 *     <li>Testing ends when the order's stock entry is approved. From then on the order is worth only what passed
 *     (passed units x unit price + shipment, with its GST), and the final part can be paid up to that.</li>
 *     <li>If more was paid than the order is then worth (units failed, or the order was cancelled or returned), a
 *     refund claim against the supplier opens by itself; a Level 2 can change its amount with a reason, and it is
 *     settled once the supplier's refunds are confirmed.</li>
 *     <li>Every entry is recorded by one person and confirmed by another; only confirmed entries count. A confirmed
 *     entry never changes: a mistake is corrected by reversing it (which is confirmed the same way).</li>
 * </ul>
 * Payments are tracked here only; the purchase itself is booked to the company account from the approved stock
 * entry (PurchasePostingRecorder).
 */
@Service
@RequiredArgsConstructor
public class SupplierPaymentService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final Set<OrderStatus> ADVANCE_FROM = EnumSet.of(OrderStatus.SUPPLIER_LEVEL_PENDING, OrderStatus.CONFIRMED,
            OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    private static final Set<OrderStatus> MIDTERM_FROM = EnumSet.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    // Nothing more is paid; whatever was paid comes back
    private static final Set<OrderStatus> CLOSED = EnumSet.of(OrderStatus.CANCELLED, OrderStatus.REFUND_COMPLETED);
    private static final List<SupplierPaymentStatus> COUNTED = List.of(SupplierPaymentStatus.PENDING_APPROVAL, SupplierPaymentStatus.APPROVED);

    private final SupplierPaymentRepository paymentRepository;
    private final SupplierRefundClaimRepository claimRepository;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final SupplierLoginService supplierLoginService;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    /** The order's payment summary and every entry, as staff see them. */
    @Transactional(readOnly = true)
    public SupplierPaymentDTOs.OrderPayments forOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> orderNotFound(orderId));
        List<SupplierPayment> payments = paymentRepository.findByOrderIdOrderByRecordedAtAscIdAsc(orderId);
        return new SupplierPaymentDTOs.OrderPayments(summary(figures(order)), toEntries(payments, Map.of(order.getId(), order), true));
    }

    /** The same for the order's supplier: only confirmed entries, and no staff names. */
    @Transactional(readOnly = true)
    public SupplierPaymentDTOs.OrderPayments forSupplier(Order order) {
        List<SupplierPayment> approved = paymentRepository.findByOrderIdAndStatusOrderByRecordedAtAscIdAsc(order.getId(), SupplierPaymentStatus.APPROVED);
        return new SupplierPaymentDTOs.OrderPayments(summary(figures(order)), toEntries(approved, Map.of(order.getId(), order), false));
    }

    /** Entries waiting for confirmation, oldest first (the Approvals Hub queue). */
    @Transactional(readOnly = true)
    public List<SupplierPaymentDTOs.Entry> waiting() {
        List<SupplierPayment> payments = paymentRepository.findByStatusOrderByRecordedAtAscIdAsc(SupplierPaymentStatus.PENDING_APPROVAL);
        Map<Long, Order> orders = orderRepository.findAllById(payments.stream().map(SupplierPayment::getOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        return toEntries(payments, orders, true);
    }

    // ------------------------------------------------------------------ recording (maker)

    @Transactional
    public SupplierPaymentDTOs.Entry record(Long orderId, SupplierPaymentDTOs.RecordRequest request, User actor) {
        Order order = lockOrder(orderId);
        if (request.getKind() == SupplierPaymentKind.REVERSAL) {
            throw ApiErrors.fieldError("kind", "Reverse a confirmed entry with Reverse; record only payments and refunds here.");
        }
        PaymentMilestone milestone = milestoneFor(request.getKind(), request.getMilestone());
        ensurePaidOnValid(order, request.getPaidOn());
        ensureAllowed(figures(order), request.getKind(), milestone, request.getAmount(), ZERO);

        SupplierPayment saved = paymentRepository.save(SupplierPayment.builder()
                .orderId(order.getId())
                .kind(request.getKind())
                .milestone(milestone)
                .amount(request.getAmount())
                .method(request.getMethod())
                .reference(OptionalText.blankToNull(request.getReference()))
                .paidOn(request.getPaidOn())
                .note(OptionalText.blankToNull(request.getNote()))
                .status(SupplierPaymentStatus.PENDING_APPROVAL)
                .recordedBy(actor.getId())
                .recordedAt(clock.instant())
                .build());
        askForConfirmation(order, saved, actor);
        return toEntry(saved, order);
    }

    /** The recorder corrects an entry that still waits for confirmation. */
    @Transactional
    public SupplierPaymentDTOs.Entry update(Long paymentId, SupplierPaymentDTOs.RecordRequest request, User actor) {
        Order order = lockOrder(orderIdOf(paymentId));
        SupplierPayment payment = lockPayment(paymentId);
        ensurePending(payment);
        ensureRecorder(payment, actor, "correct");
        if (payment.getKind() == SupplierPaymentKind.REVERSAL || request.getKind() != payment.getKind()) {
            throw ApiErrors.fieldError("kind", payment.getKind() == SupplierPaymentKind.REVERSAL
                    ? "A reversal can't be corrected; withdraw it instead." : "A payment can't be turned into a refund or back; withdraw it and record a new one.");
        }
        PaymentMilestone milestone = milestoneFor(payment.getKind(), request.getMilestone());
        ensurePaidOnValid(order, request.getPaidOn());
        ensureAllowed(figures(order), payment.getKind(), milestone, request.getAmount(), payment.getAmount());

        payment.setMilestone(milestone);
        payment.setAmount(request.getAmount());
        payment.setMethod(request.getMethod());
        payment.setReference(OptionalText.blankToNull(request.getReference()));
        payment.setPaidOn(request.getPaidOn());
        payment.setNote(OptionalText.blankToNull(request.getNote()));
        payment.setRecordedAt(clock.instant());
        return toEntry(paymentRepository.save(payment), order);
    }

    @Transactional
    public SupplierPaymentDTOs.Entry withdraw(Long paymentId, User actor) {
        Order order = lockOrder(orderIdOf(paymentId));
        SupplierPayment payment = lockPayment(paymentId);
        ensurePending(payment);
        ensureRecorder(payment, actor, "withdraw");
        payment.setStatus(SupplierPaymentStatus.WITHDRAWN);
        payment.setDecidedBy(actor.getId());
        payment.setDecidedAt(clock.instant());
        return toEntry(paymentRepository.save(payment), order);
    }

    /** Asks to undo a confirmed payment or refund: a reversal of the same amount, confirmed like any entry. */
    @Transactional
    public SupplierPaymentDTOs.Entry reverse(Long paymentId, SupplierPaymentDTOs.ReverseRequest request, User actor) {
        Order order = lockOrder(orderIdOf(paymentId));
        SupplierPayment original = lockPayment(paymentId);
        if (original.getStatus() != SupplierPaymentStatus.APPROVED || original.getKind() == SupplierPaymentKind.REVERSAL) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_PENDING, "Only a confirmed payment or refund can be reversed.");
        }
        if (paymentRepository.existsByReversesIdAndStatusIn(original.getId(), COUNTED)) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_ALREADY_REVERSED, "This entry is already reversed, or its reversal waits for confirmation.");
        }
        SupplierPayment saved = paymentRepository.save(SupplierPayment.builder()
                .orderId(order.getId())
                .kind(SupplierPaymentKind.REVERSAL)
                // A payment's milestone: it comes off that part. A refund's reversal has none
                .milestone(original.getKind() == SupplierPaymentKind.PAYMENT ? original.getMilestone() : null)
                .amount(original.getAmount())
                .method(original.getMethod())
                .reference(original.getReference())
                .paidOn(LocalDate.now(clock))
                .note(request.getReason().trim())
                .reversesId(original.getId())
                .status(SupplierPaymentStatus.PENDING_APPROVAL)
                .recordedBy(actor.getId())
                .recordedAt(clock.instant())
                .build());
        askForConfirmation(order, saved, actor);
        return toEntry(saved, order);
    }

    // ------------------------------------------------------------------ deciding (checker)

    /** A different person confirms or rejects an entry; confirming re-checks it against the order as it is now. */
    @Transactional
    public SupplierPaymentDTOs.Entry decide(Long paymentId, SupplierPaymentDTOs.DecisionRequest request, User approver) {
        Order order = lockOrder(orderIdOf(paymentId));
        SupplierPayment payment = lockPayment(paymentId);
        ensurePending(payment);
        if (payment.getRecordedBy().equals(approver.getId())) {
            throw ApiErrors.forbidden(ErrorCodes.SUPPLIER_PAYMENT_SELF_DECISION,
                    "You recorded this entry, so someone else has to confirm or reject it.");
        }
        boolean approved = request.getDecision() == SupplierPaymentDTOs.Decision.APPROVED;
        String reason = OptionalText.blankToNull(request.getReason());
        if (!approved && reason == null) {
            throw ApiErrors.fieldError("reason", "A reason is required to reject a payment entry.");
        }
        if (approved && payment.getKind() != SupplierPaymentKind.REVERSAL) {
            ensureAllowed(figures(order), payment.getKind(), payment.getMilestone(), payment.getAmount(), payment.getAmount());
        }

        payment.setStatus(approved ? SupplierPaymentStatus.APPROVED : SupplierPaymentStatus.REJECTED);
        payment.setDecidedBy(approver.getId());
        payment.setDecidedAt(clock.instant());
        payment.setDecisionReason(reason);
        SupplierPayment saved = paymentRepository.saveAndFlush(payment);
        if (approved) {
            reconcile(order);
        }

        notificationService.createForUser(payment.getRecordedBy(), approved ? "Supplier payment confirmed" : "Supplier payment rejected",
                describe(saved) + " for order " + order.getOrderNumber() + " was " + (approved ? "confirmed." : "rejected.")
                        + (reason != null ? " Reason: " + reason : ""), NotificationLinks.ORDERS);
        if (approved) {
            supplierLoginService.activeUserIdOf(order.getSupplier().getId()).ifPresent(supplierUser ->
                    notificationService.createForUser(supplierUser, "Payment recorded for order " + order.getOrderNumber(),
                            describe(saved) + " for order " + order.getOrderNumber() + " was recorded.", NotificationLinks.SUPPLIER_ORDERS));
        }
        return toEntry(saved, order);
    }

    /** A Level 2 changes what the supplier must pay back (at most what was overpaid), with a reason. */
    @Transactional
    public SupplierPaymentDTOs.Summary adjustClaim(Long orderId, SupplierPaymentDTOs.ClaimAdjustRequest request, User approver) {
        Order order = lockOrder(orderId);
        SupplierRefundClaim claim = claimRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + order.getOrderNumber() + " has no refund claim."));
        if (request.getAgreedAmount().compareTo(claim.getClaimedAmount()) > 0) {
            throw ApiErrors.fieldError("agreedAmount", "The supplier can be asked for at most what was overpaid, " + rupees(claim.getClaimedAmount()) + ".");
        }
        claim.setAgreedAmount(request.getAgreedAmount());
        claim.setAdjustReason(request.getReason().trim());
        claim.setAdjustedBy(approver.getId());
        claim.setAdjustedAt(clock.instant());
        claimRepository.saveAndFlush(claim);
        reconcile(order);
        return summary(figures(order));
    }

    // ------------------------------------------------------------------ order and stock events

    /**
     * Brings the order's refund claim in line with its figures: after its stock entry is decided (testing), after it
     * is cancelled or its return is completed, and after each confirmed entry. The caller holds the order's lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(Order order) {
        Figures figures = figures(order);
        SupplierRefundClaim claim = claimRepository.findByOrderId(order.getId()).orElse(null);
        boolean settledFigures = figures.tested() || figures.closed();
        BigDecimal overpaid = settledFigures ? figures.paid().subtract(figures.payable()).max(ZERO) : ZERO;
        if (claim == null) {
            if (overpaid.signum() <= 0) {
                return;
            }
            claim = SupplierRefundClaim.builder()
                    .orderId(order.getId())
                    .openedAt(clock.instant())
                    .status(RefundClaimStatus.OPEN)
                    .agreedAmount(overpaid)
                    .build();
            notifyClaimOpened(order, overpaid, figures);
        }
        claim.setCause(causeOf(order));
        claim.setFailedUnits(figures.unitsFailed());
        claim.setClaimedAmount(overpaid);
        // Until a Level 2 changes it, the supplier owes exactly what was overpaid
        if (claim.getAdjustedBy() == null) {
            claim.setAgreedAmount(overpaid);
        } else if (claim.getAgreedAmount().compareTo(overpaid) > 0) {
            claim.setAgreedAmount(overpaid);
        }
        boolean open = claim.getAgreedAmount().compareTo(figures.refunded()) > 0;
        claim.setStatus(open ? RefundClaimStatus.OPEN : RefundClaimStatus.SETTLED);
        claim.setSettledAt(open ? null : Objects.requireNonNullElse(claim.getSettledAt(), clock.instant()));
        claimRepository.save(claim);
    }

    // ------------------------------------------------------------------ rules

    private record Figures(Order order, boolean tested, boolean closed, BigDecimal orderValue, BigDecimal payable,
                           BigDecimal unitsPassed, BigDecimal unitsFailed,
                           Map<PaymentMilestone, BigDecimal> paidBy, Map<PaymentMilestone, BigDecimal> pendingBy,
                           BigDecimal refunded, BigDecimal pendingRefunds) {

        BigDecimal paid() {
            return sum(paidBy.values());
        }

        BigDecimal pendingPayments() {
            return sum(pendingBy.values());
        }

        BigDecimal plannedAdvance() {
            return closed ? ZERO : PaymentPlan.share(orderValue, order.getAdvancePercent());
        }

        BigDecimal plannedMidterm() {
            return closed ? ZERO : PaymentPlan.share(orderValue, order.getMidtermPercent());
        }

        // What is left for after testing: of the quoted total until then, of what passed afterwards
        BigDecimal plannedFinal() {
            return closed ? ZERO : payable.subtract(plannedAdvance()).subtract(plannedMidterm()).max(ZERO);
        }

        /** The most that may have been paid in all, now. */
        BigDecimal cap() {
            if (closed) {
                return ZERO;
            }
            return tested ? payable : plannedAdvance().add(plannedMidterm());
        }

        // A plan may have no midterm part (0 %): only an advance and the final payment. Orders without a plan keep it
        boolean hasMidterm() {
            return order.getMidtermPercent() == null || order.getMidtermPercent().signum() > 0;
        }

        List<PaymentMilestone> openMilestones() {
            List<PaymentMilestone> open = new ArrayList<>();
            if (closed) {
                return open;
            }
            if (ADVANCE_FROM.contains(order.getOrderStatus())) open.add(PaymentMilestone.ADVANCE);
            if (hasMidterm() && MIDTERM_FROM.contains(order.getOrderStatus())) open.add(PaymentMilestone.MIDTERM);
            if (tested) open.add(PaymentMilestone.FINAL);
            return open;
        }

        boolean due(PaymentMilestone milestone) {
            return switch (milestone) {
                case ADVANCE -> !closed && ADVANCE_FROM.contains(order.getOrderStatus());
                case MIDTERM -> !closed && hasMidterm() && MIDTERM_FROM.contains(order.getOrderStatus());
                case FINAL -> !closed && tested;
            };
        }
    }

    private Figures figures(Order order) {
        Stock stock = stockRepository.findFirstByOrderOrderNumber(order.getOrderNumber()).orElse(null);
        boolean tested = stock != null && stock.getApprovalStatus() == ApprovalStatus.APPROVED && stock.getApprovedUnitsPassed() != null;
        boolean closed = CLOSED.contains(order.getOrderStatus());
        BigDecimal orderValue = QuotationTax.grandTotal(order.getTaxBreakup(), order.getTotalPrice());
        BigDecimal payable;
        if (closed) {
            payable = ZERO;
        } else if (tested) {
            BigDecimal value = PurchasePostingRecorder.purchaseValue(order, stock);
            payable = value.add(PurchasePostingRecorder.gstOn(order, value).total());
        } else {
            payable = orderValue;
        }

        Map<PaymentMilestone, BigDecimal> paidBy = new EnumMap<>(PaymentMilestone.class);
        Map<PaymentMilestone, BigDecimal> pendingBy = new EnumMap<>(PaymentMilestone.class);
        for (PaymentMilestone milestone : PaymentMilestone.values()) {
            paidBy.put(milestone, ZERO);
            pendingBy.put(milestone, ZERO);
        }
        BigDecimal refunded = ZERO;
        BigDecimal pendingRefunds = ZERO;
        for (SupplierPaymentRepository.Total total : paymentRepository.totalsForOrder(order.getId(), COUNTED)) {
            BigDecimal amount = total.getTotal();
            boolean approved = total.getStatus() == SupplierPaymentStatus.APPROVED;
            PaymentMilestone milestone = total.getMilestone();
            switch (total.getKind()) {
                case PAYMENT -> (approved ? paidBy : pendingBy).merge(milestone, amount, BigDecimal::add);
                case REFUND -> {
                    if (approved) refunded = refunded.add(amount);
                    else pendingRefunds = pendingRefunds.add(amount);
                }
                // Only a confirmed reversal counts; one waiting changes nothing yet
                case REVERSAL -> {
                    if (approved && milestone != null) paidBy.merge(milestone, amount.negate(), BigDecimal::add);
                    else if (approved) refunded = refunded.subtract(amount);
                }
            }
        }
        BigDecimal passed = tested ? stock.getApprovedUnitsPassed() : BigDecimal.ZERO;
        BigDecimal failed = tested ? Objects.requireNonNullElse(stock.getApprovedDefectedUnits(), BigDecimal.ZERO) : BigDecimal.ZERO;
        return new Figures(order, tested, closed, orderValue, payable, passed, failed, paidBy, pendingBy, refunded, pendingRefunds);
    }

    private SupplierPaymentDTOs.Summary summary(Figures figures) {
        Order order = figures.order();
        List<SupplierPaymentDTOs.Milestone> milestones = Stream.of(PaymentMilestone.values()).map(milestone -> SupplierPaymentDTOs.Milestone.builder()
                .milestone(milestone)
                .percent(switch (milestone) {
                    case ADVANCE -> order.getAdvancePercent();
                    case MIDTERM -> order.getMidtermPercent();
                    case FINAL -> PaymentPlan.finalPercent(order.getAdvancePercent(), order.getMidtermPercent());
                })
                .planned(switch (milestone) {
                    case ADVANCE -> figures.plannedAdvance();
                    case MIDTERM -> figures.plannedMidterm();
                    case FINAL -> figures.plannedFinal();
                })
                .paid(figures.paidBy().get(milestone))
                .pending(figures.pendingBy().get(milestone))
                .due(figures.due(milestone))
                .build()).toList();
        BigDecimal netPaid = figures.paid().subtract(figures.refunded());
        List<PaymentMilestone> open = figures.openMilestones();
        BigDecimal payableNow = open.isEmpty() ? ZERO : figures.cap().subtract(netPaid).subtract(figures.pendingPayments()).max(ZERO);
        SupplierRefundClaim claim = claimRepository.findByOrderId(order.getId()).orElse(null);
        return SupplierPaymentDTOs.Summary.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .orderStatus(order.getOrderStatus())
                .hasPlan(order.getAdvancePercent() != null)
                .advancePercent(order.getAdvancePercent())
                .midtermPercent(order.getMidtermPercent())
                .finalPercent(PaymentPlan.finalPercent(order.getAdvancePercent(), order.getMidtermPercent()))
                .expectedPaymentMethod(order.getExpectedPaymentMethod())
                .orderValue(figures.orderValue())
                .tested(figures.tested())
                .unitsPassed(figures.unitsPassed())
                .unitsFailed(figures.unitsFailed())
                .payable(figures.payable())
                .paid(figures.paid())
                .pendingPayments(figures.pendingPayments())
                .refunded(figures.refunded())
                .pendingRefunds(figures.pendingRefunds())
                .outstanding(figures.payable().subtract(netPaid).max(ZERO))
                .payableNow(payableNow)
                .openMilestones(open)
                .milestones(milestones)
                .claim(claim == null ? null : SupplierPaymentDTOs.Claim.builder()
                        .id(claim.getId())
                        .cause(claim.getCause())
                        .failedUnits(claim.getFailedUnits())
                        .claimedAmount(claim.getClaimedAmount())
                        .agreedAmount(claim.getAgreedAmount())
                        .adjustReason(claim.getAdjustReason())
                        .adjustedAt(claim.getAdjustedAt())
                        .refunded(figures.refunded())
                        .refundDue(claim.getAgreedAmount().subtract(figures.refunded()).max(ZERO))
                        .status(claim.getStatus())
                        .openedAt(claim.getOpenedAt())
                        .settledAt(claim.getSettledAt())
                        .build())
                .build();
    }

    /**
     * A payment fits the plan and the order's stage; a refund fits the open claim. {@code ownPending} is the amount of
     * the entry being corrected or confirmed, already counted as waiting.
     */
    private void ensureAllowed(Figures figures, SupplierPaymentKind kind, PaymentMilestone milestone, BigDecimal amount, BigDecimal ownPending) {
        Order order = figures.order();
        if (kind == SupplierPaymentKind.REFUND) {
            SupplierRefundClaim claim = claimRepository.findByOrderId(order.getId()).orElse(null);
            BigDecimal left = claim == null || claim.getStatus() != RefundClaimStatus.OPEN ? ZERO
                    : claim.getAgreedAmount().subtract(figures.refunded()).subtract(figures.pendingRefunds()).add(ownPending).max(ZERO);
            if (claim == null || claim.getStatus() != RefundClaimStatus.OPEN) {
                throw ApiErrors.conflict(ErrorCodes.SUPPLIER_REFUND_NOT_DUE, "The supplier owes nothing back for order " + order.getOrderNumber() + ".");
            }
            if (amount.compareTo(left) > 0) {
                throw ApiErrors.fieldError("amount", "The supplier still owes " + rupees(left) + " (not counting refunds waiting for confirmation).");
            }
            return;
        }
        if (!figures.openMilestones().contains(milestone)) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_DUE, notDueMessage(figures, milestone));
        }
        BigDecimal netPaid = figures.paid().subtract(figures.refunded());
        BigDecimal left = figures.cap().subtract(netPaid).subtract(figures.pendingPayments()).add(ownPending).max(ZERO);
        if (amount.compareTo(left) > 0) {
            throw ApiErrors.fieldError("amount", left.signum() == 0
                    ? "Nothing more can be paid for order " + order.getOrderNumber() + " now. " + capHint(figures)
                    : "At most " + rupees(left) + " more can be paid for order " + order.getOrderNumber() + " now. " + capHint(figures));
        }
    }

    private static String capHint(Figures figures) {
        if (figures.tested()) {
            return "After testing the order is worth " + rupees(figures.payable()) + " (the units that passed, with shipment and GST).";
        }
        return "Until the delivered units are tested, only the advance and midterm parts (" + rupees(figures.cap())
                + " of " + rupees(figures.orderValue()) + ") can be paid; the final part waits for testing.";
    }

    private static String notDueMessage(Figures figures, PaymentMilestone milestone) {
        Order order = figures.order();
        if (figures.closed()) {
            return "Order " + order.getOrderNumber() + " is " + order.getOrderStatus().label() + ": nothing more is paid for it.";
        }
        return switch (milestone) {
            case ADVANCE -> "The advance can be paid once order " + order.getOrderNumber() + " is approved (it is " + order.getOrderStatus().label() + ").";
            case MIDTERM -> !figures.hasMidterm()
                    ? "Order " + order.getOrderNumber() + "'s payment plan has no midterm part: pay the advance, and the rest after testing."
                    : "The midterm payment is due once the supplier has shipped order " + order.getOrderNumber() + " (it is "
                    + order.getOrderStatus().label() + ").";
            case FINAL -> "The final payment is made only after the delivered units are tested: approve the stock entry of order "
                    + order.getOrderNumber() + " first.";
        };
    }

    private static PaymentMilestone milestoneFor(SupplierPaymentKind kind, PaymentMilestone milestone) {
        if (kind == SupplierPaymentKind.PAYMENT && milestone == null) {
            throw ApiErrors.fieldError("milestone", "Say which part of the plan this pays: advance, midterm or final.");
        }
        return kind == SupplierPaymentKind.PAYMENT ? milestone : null;
    }

    private static void ensurePaidOnValid(Order order, LocalDate paidOn) {
        if (paidOn != null && order.getOrderDate() != null && paidOn.isBefore(order.getOrderDate())) {
            throw ApiErrors.fieldError("paidOn", "The payment date cannot be before the order date (" + order.getOrderDate() + ").");
        }
    }

    private static void ensurePending(SupplierPayment payment) {
        if (payment.getStatus() != SupplierPaymentStatus.PENDING_APPROVAL) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_PENDING, "This entry was already " + payment.getStatus().name().toLowerCase().replace('_', ' ') + ".");
        }
    }

    private static void ensureRecorder(SupplierPayment payment, User actor, String action) {
        if (!payment.getRecordedBy().equals(actor.getId())) {
            throw ApiErrors.forbidden(ErrorCodes.SUPPLIER_PAYMENT_NOT_YOURS, "Only the person who recorded this entry can " + action + " it.");
        }
    }

    private static RefundClaimCause causeOf(Order order) {
        return switch (order.getOrderStatus()) {
            case CANCELLED -> RefundClaimCause.ORDER_CANCELLED;
            case REFUND_COMPLETED -> RefundClaimCause.ORDER_RETURNED;
            default -> RefundClaimCause.FAILED_TESTING;
        };
    }

    // ------------------------------------------------------------------ notifications and mapping

    private void askForConfirmation(Order order, SupplierPayment payment, User actor) {
        notificationService.createForPermission(Permission.SUPPLIER_PAYMENT_APPROVE, actor.getId(), "Supplier payment awaiting confirmation",
                describe(payment) + " for order " + order.getOrderNumber() + " (" + order.getSupplier().getName()
                        + ") was recorded and waits for someone else to confirm it.", NotificationLinks.APPROVALS);
    }

    private void notifyClaimOpened(Order order, BigDecimal amount, Figures figures) {
        String why = switch (causeOf(order)) {
            case ORDER_CANCELLED -> "it was cancelled after a payment";
            case ORDER_RETURNED -> "it was returned";
            case FAILED_TESTING -> figures.unitsFailed().signum() > 0
                    ? figures.unitsFailed().stripTrailingZeros().toPlainString() + " " + order.getUnitType() + " failed testing"
                    : "fewer units passed testing than were paid for";
        };
        String message = "More was paid for order " + order.getOrderNumber() + " than it is now worth (" + why + "): "
                + rupees(amount) + " is to be refunded by " + order.getSupplier().getName() + ".";
        notificationService.createForPermission(Permission.SUPPLIER_PAYMENT_RECORD, null, "Refund due from a supplier", message, NotificationLinks.ORDERS);
        supplierLoginService.activeUserIdOf(order.getSupplier().getId()).ifPresent(supplierUser ->
                notificationService.createForUser(supplierUser, "Refund due for order " + order.getOrderNumber(), message, NotificationLinks.SUPPLIER_ORDERS));
    }

    private static String describe(SupplierPayment payment) {
        String what = switch (payment.getKind()) {
            case PAYMENT -> label(payment.getMilestone()) + " payment of " + rupees(payment.getAmount());
            case REFUND -> "Refund of " + rupees(payment.getAmount()) + " from the supplier";
            case REVERSAL -> "Reversal of " + rupees(payment.getAmount());
        };
        return what + " (" + payment.getMethod().name().replace('_', ' ').toLowerCase() + ")";
    }

    private static String label(PaymentMilestone milestone) {
        return switch (milestone) {
            case ADVANCE -> "Advance";
            case MIDTERM -> "Midterm";
            case FINAL -> "Final";
        };
    }

    static String rupees(BigDecimal amount) {
        return "₹" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private SupplierPaymentDTOs.Entry toEntry(SupplierPayment payment, Order order) {
        return toEntries(List.of(payment), Map.of(order.getId(), order), true).getFirst();
    }

    private List<SupplierPaymentDTOs.Entry> toEntries(List<SupplierPayment> payments, Map<Long, Order> orders, boolean withStaff) {
        Map<Long, User> users = withStaff ? users(payments) : Map.of();
        // Approved entries whose reversal is confirmed or waiting, found in the same list (an order's entries) or not at all
        Set<Long> reversed = payments.stream()
                .filter(p -> p.getKind() == SupplierPaymentKind.REVERSAL && COUNTED.contains(p.getStatus()))
                .map(SupplierPayment::getReversesId).collect(Collectors.toSet());
        return payments.stream().map(payment -> {
            Order order = orders.get(payment.getOrderId());
            return SupplierPaymentDTOs.Entry.builder()
                    .id(payment.getId())
                    .orderId(payment.getOrderId())
                    .orderNumber(order != null ? order.getOrderNumber() : null)
                    .supplierName(order != null ? order.getSupplier().getName() : null)
                    .kind(payment.getKind())
                    .milestone(payment.getMilestone())
                    .amount(payment.getAmount())
                    .method(payment.getMethod())
                    .reference(payment.getReference())
                    .paidOn(payment.getPaidOn())
                    .note(payment.getNote())
                    .reversesId(payment.getReversesId())
                    .reversed(reversed.contains(payment.getId()))
                    .status(payment.getStatus())
                    .recordedBy(withStaff ? payment.getRecordedBy() : null)
                    .recordedByUsername(usernameOf(users, payment.getRecordedBy()))
                    .recordedAt(payment.getRecordedAt())
                    .decidedByUsername(usernameOf(users, payment.getDecidedBy()))
                    .decidedAt(payment.getDecidedAt())
                    .decisionReason(withStaff ? payment.getDecisionReason() : null)
                    .build();
        }).toList();
    }

    private Map<Long, User> users(Collection<SupplierPayment> payments) {
        Set<Long> ids = payments.stream().flatMap(p -> Stream.of(p.getRecordedBy(), p.getDecidedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static String usernameOf(Map<Long, User> users, Long userId) {
        User user = userId == null ? null : users.get(userId);
        return user != null ? user.getUsername() : null;
    }

    private Long orderIdOf(Long paymentId) {
        return paymentRepository.findById(paymentId).map(SupplierPayment::getOrderId).orElseThrow(() -> paymentNotFound(paymentId));
    }

    // Order first, then the entry: the same order every writer of an order's money takes its locks in
    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> orderNotFound(orderId));
    }

    private SupplierPayment lockPayment(Long paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
    }

    private static BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream().reduce(ZERO, BigDecimal::add);
    }

    private static ResourceNotFoundException orderNotFound(Long orderId) {
        return new ResourceNotFoundException("Order not found with id: " + orderId);
    }

    private static ResourceNotFoundException paymentNotFound(Long paymentId) {
        return new ResourceNotFoundException("Supplier payment not found with id: " + paymentId);
    }
}
