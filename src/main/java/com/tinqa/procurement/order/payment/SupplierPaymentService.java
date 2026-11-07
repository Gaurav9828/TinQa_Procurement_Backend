package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.document.constant.DocumentCategory;
import com.tinqa.procurement.document.constant.DocumentOwnerType;
import com.tinqa.procurement.document.constant.DocumentPurpose;
import com.tinqa.procurement.document.constant.DocumentReferenceType;
import com.tinqa.procurement.document.constant.DocumentStage;
import com.tinqa.procurement.document.constant.DocumentStatus;
import com.tinqa.procurement.document.constant.DocumentType;
import com.tinqa.procurement.document.constant.DocumentUploaderType;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.document.entity.Document;
import com.tinqa.procurement.document.repository.DocumentRepository;
import com.tinqa.procurement.document.service.FileStorageService;
import com.tinqa.procurement.document.service.UploadedFileValidator;
import com.tinqa.procurement.employee.service.ActorDirectory;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
 *     <li>A payment's amount is set by the plan: what is still owed on its part (planned less paid and waiting),
 *     within that cap. Staff choose the part and the method; they don't type the amount.</li>
 *     <li>A payment starts as a Finance Manager's request, which only a Super Admin approves (TO_PAY) or rejects; no
 *     money moves before that. The Finance Manager then pays it and gives its reference (AWAITING_SUPPLIER). It counts
 *     as paid only once the supplier confirms in their portal that they received it. When they say it did not arrive
 *     (NOT_RECEIVED), the Finance Manager re-checks it and sends it again, optionally with a proof file, until the
 *     supplier confirms. Until then it is held against its part and the cap.</li>
 *     <li>Payment requests are approved, and paid, in plan order: the midterm only after the advance, the final part
 *     only after the midterm (all may be requested at once).</li>
 *     <li>Money back from the supplier is paid by the supplier and checked by a Finance Manager, with no Super Admin
 *     step: a reversal of a payment they confirmed (asked by a Finance Manager), a return of failed pieces once the pieces
 *     reached them (asked by the system), or a refund due on the claim (offered by the supplier). The supplier gives the
 *     reference and maybe a receipt; a Finance Manager confirms it arrived or says it did not, and the supplier sends it
 *     again. A confirmed entry never changes: a mistake is corrected by reversing it. A reversal of a refund (money
 *     going back out to the supplier) is decided by a Super Admin.</li>
 *     <li>Once the order is delivered and tested, fully paid, and every payment received by the supplier (nothing
 *     waiting, no reversal open), its reversal window closes: no payment or refund of the order can be reversed any more
 *     (409 SUPPLIER_PAYMENT_REVERSAL_CLOSED, Summary.reversalClosed).</li>
 *     <li>Every step is kept in supplier_payment_event, in the same transaction.</li>
 * </ul>
 * Payments are tracked here only; the purchase itself is booked to the company account from the approved stock
 * entry (PurchasePostingRecorder).
 */
@Service
@RequiredArgsConstructor
public class SupplierPaymentService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    // The advance is paid once the supplier has accepted the order (not on approval alone)
    private static final Set<OrderStatus> ADVANCE_FROM = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    private static final Set<OrderStatus> MIDTERM_FROM = EnumSet.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    // Nothing more is paid; whatever was paid comes back
    private static final Set<OrderStatus> CLOSED = EnumSet.of(OrderStatus.CANCELLED, OrderStatus.REFUND_COMPLETED);
    // The order reached the supplier: delivered, or a return that started from delivery
    private static final Set<OrderStatus> DELIVERED_ON = EnumSet.of(OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED,
            OrderStatus.RETURN_APPROVED, OrderStatus.RETURN_REJECTED, OrderStatus.RETURN_PICKED_UP, OrderStatus.RETURN_RECEIVED,
            OrderStatus.REFUND_INITIATED, OrderStatus.REFUND_FAILED, OrderStatus.REFUND_COMPLETED);
    // Entries that count: confirmed, or still on their way (requested, to pay, with the supplier, sent back, paid back)
    private static final List<SupplierPaymentStatus> COUNTED = List.of(SupplierPaymentStatus.PENDING_APPROVAL, SupplierPaymentStatus.TO_PAY,
            SupplierPaymentStatus.AWAITING_SUPPLIER, SupplierPaymentStatus.NOT_RECEIVED, SupplierPaymentStatus.APPROVED,
            SupplierPaymentStatus.REPAYMENT_DUE, SupplierPaymentStatus.REPAYMENT_SENT, SupplierPaymentStatus.REPAYMENT_MISSING);
    // What the supplier sees: confirmed entries, payments made to them that wait for (or failed) their receipt, and the
    // money they are asked to pay back
    private static final List<SupplierPaymentStatus> SUPPLIER_SEES = List.of(SupplierPaymentStatus.AWAITING_SUPPLIER,
            SupplierPaymentStatus.NOT_RECEIVED, SupplierPaymentStatus.APPROVED, SupplierPaymentStatus.REPAYMENT_DUE,
            SupplierPaymentStatus.REPAYMENT_SENT, SupplierPaymentStatus.REPAYMENT_MISSING);
    // Money back the supplier still has to pay (asked of them, or not found in the bank)
    private static final List<SupplierPaymentStatus> REPAYMENT_OPEN = List.of(SupplierPaymentStatus.REPAYMENT_DUE,
            SupplierPaymentStatus.REPAYMENT_MISSING);
    private static final List<SupplierPaymentStatus> TO_MAKE = List.of(SupplierPaymentStatus.TO_PAY, SupplierPaymentStatus.NOT_RECEIVED);
    // A payment's requester may take it back while it waits on TinQa (not while the supplier holds it)
    private static final Set<SupplierPaymentStatus> WITHDRAWABLE_PAYMENT = EnumSet.of(SupplierPaymentStatus.PENDING_APPROVAL,
            SupplierPaymentStatus.TO_PAY, SupplierPaymentStatus.NOT_RECEIVED);
    private static final Set<String> PROOF_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "application/pdf");
    private static final long MAX_PROOF_BYTES = 5L * 1024 * 1024;

    private final SupplierPaymentRepository paymentRepository;
    private final SupplierRefundClaimRepository claimRepository;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final SupplierLoginService supplierLoginService;
    private final SupplierPaymentEventRepository eventRepository;
    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final UploadedFileValidator uploadedFileValidator;
    private final ActorDirectory actorDirectory;
    private final com.tinqa.procurement.order.supplierreturn.SupplierReturnRepository returnRepository;
    private final com.tinqa.procurement.order.supplierreturn.SupplierReturnEventRepository returnEventRepository;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    /** The order's payment summary and every entry, as staff see them. */
    @Transactional(readOnly = true)
    public SupplierPaymentDTOs.OrderPayments forOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> orderNotFound(orderId));
        List<SupplierPayment> payments = paymentRepository.findByOrderIdOrderByRecordedAtAscIdAsc(orderId);
        Figures figures = figures(order);
        List<SupplierPaymentDTOs.Entry> entries = toEntries(payments, Map.of(order.getId(), order), true);
        entries.forEach(entry -> {
            if (entry.getKind() == SupplierPaymentKind.PAYMENT && entry.getStatus() == SupplierPaymentStatus.PENDING_APPROVAL) {
                entry.setWaitsFor(earlierToApprove(figures, entry.getMilestone()));
            }
        });
        return new SupplierPaymentDTOs.OrderPayments(summary(figures), entries);
    }

    /** The same for the order's supplier: confirmed entries and payments waiting for their receipt, no staff names. */
    @Transactional(readOnly = true)
    public SupplierPaymentDTOs.OrderPayments forSupplier(Order order) {
        List<SupplierPayment> shown = paymentRepository.findByOrderIdAndStatusInOrderByRecordedAtAscIdAsc(order.getId(), SUPPLIER_SEES);
        return new SupplierPaymentDTOs.OrderPayments(summary(figures(order)), toEntries(shown, Map.of(order.getId(), order), false));
    }

    /** Per order, how many payments wait for the supplier to confirm receipt (one statement for the list). */
    @Transactional(readOnly = true)
    public Map<Long, Long> paymentsAwaitingSupplier(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return paymentRepository.countAwaitingSupplier(orderIds).stream()
                .collect(Collectors.toMap(SupplierPaymentRepository.OrderCount::getOrderId, SupplierPaymentRepository.OrderCount::getCount));
    }

    /** Per order, the repayments the supplier still has to pay (asked of them, or not found in the bank). */
    @Transactional(readOnly = true)
    public Map<Long, Long> repaymentsDue(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return paymentRepository.countByOrderAndStatuses(orderIds, REPAYMENT_OPEN).stream()
                .collect(Collectors.toMap(SupplierPaymentRepository.OrderCount::getOrderId, SupplierPaymentRepository.OrderCount::getCount));
    }

    /** The order a payment entry belongs to (the supplier portal checks that it is the supplier's own). */
    @Transactional(readOnly = true)
    public Long orderIdOfPayment(Long paymentId) {
        return orderIdOf(paymentId);
    }

    /** Payment requests, refunds and reversals waiting for a decision, oldest first (the Approvals Hub queue). */
    @Transactional(readOnly = true)
    public List<SupplierPaymentDTOs.Entry> waiting() {
        List<SupplierPaymentDTOs.Entry> entries = withOrders(paymentRepository.findByStatusOrderByRecordedAtAscIdAsc(SupplierPaymentStatus.PENDING_APPROVAL));
        // Plan order, from the queue itself (no query per order): an earlier part of the same order still waits here.
        // Approving re-checks the whole rule (an earlier part never requested) against the order's figures
        entries.forEach(entry -> {
            if (entry.getKind() != SupplierPaymentKind.PAYMENT) {
                return;
            }
            entries.stream()
                    .filter(other -> other.getKind() == SupplierPaymentKind.PAYMENT && other.getOrderId().equals(entry.getOrderId())
                            && other.getMilestone().ordinal() < entry.getMilestone().ordinal())
                    .map(SupplierPaymentDTOs.Entry::getMilestone)
                    .min(java.util.Comparator.naturalOrder())
                    .ifPresent(entry::setWaitsFor);
        });
        return entries;
    }

    /** Money suppliers say they paid back, for a Finance Manager to check, oldest first. */
    @Transactional(readOnly = true)
    public List<SupplierPaymentDTOs.Entry> repaymentsToCheck() {
        return withOrders(paymentRepository.findByStatusOrderByRecordedAtAscIdAsc(SupplierPaymentStatus.REPAYMENT_SENT));
    }

    /** Approved requests to pay, and payments the supplier says did not arrive, oldest first (the Finance Manager's queue). */
    @Transactional(readOnly = true)
    public List<SupplierPaymentDTOs.Entry> toMake() {
        return withOrders(paymentRepository.findByStatusInOrderByRecordedAtAscIdAsc(TO_MAKE));
    }

    private List<SupplierPaymentDTOs.Entry> withOrders(List<SupplierPayment> payments) {
        Map<Long, Order> orders = orderRepository.findAllById(payments.stream().map(SupplierPayment::getOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        return toEntries(payments, orders, true);
    }

    /** A proof file sent with a payment, for staff who see the order. */
    @Transactional(readOnly = true)
    public DocumentDownload proofFile(Long paymentId, Long documentId) {
        if (!eventRepository.existsByPaymentIdAndProofDocumentId(paymentId, documentId)) {
            throw new ResourceNotFoundException("No proof " + documentId + " was sent with supplier payment " + paymentId + ".");
        }
        Document document = documentRepository.findById(documentId)
                .filter(found -> found.getReferenceType() == DocumentReferenceType.SUPPLIER_PAYMENT && paymentId.equals(found.getReferenceId()))
                .orElseThrow(() -> new ResourceNotFoundException("Proof file not found: " + documentId));
        return new DocumentDownload(fileStorageService.loadFileAsResource(document.getStorageKey()),
                document.getContentType(), document.getOriginalFileName());
    }

    // ------------------------------------------------------------------ recording (maker)

    /**
     * A payment request (no money moves until a Super Admin approves it; its reference and date are given when it is
     * paid), or a refund the supplier paid back.
     */
    @Transactional
    public SupplierPaymentDTOs.Entry record(Long orderId, SupplierPaymentDTOs.RecordRequest request, User actor) {
        Order order = lockOrder(orderId);
        if (request.getKind() == SupplierPaymentKind.REVERSAL) {
            throw ApiErrors.fieldError("kind", "Reverse a confirmed entry with Reverse; request only payments here.");
        }
        if (request.getKind() == SupplierPaymentKind.REFUND) {
            throw ApiErrors.fieldError("kind", "The supplier pays a refund back in their supplier login, with its reference and "
                    + "receipt; a Finance Manager then checks that it arrived.");
        }
        boolean payment = request.getKind() == SupplierPaymentKind.PAYMENT;
        PaymentMilestone milestone = milestoneFor(request.getKind(), request.getMilestone());
        if (!payment) {
            ensureRefundDetails(order, request);
        }
        BigDecimal amount = allowedAmount(figures(order), request.getKind(), milestone, request.getAmount(), null);
        ensureSupplierCanConfirm(order, request.getKind());

        SupplierPayment saved = paymentRepository.save(SupplierPayment.builder()
                .orderId(order.getId())
                .kind(request.getKind())
                .milestone(milestone)
                .amount(amount)
                .method(request.getMethod())
                .reference(payment ? null : OptionalText.blankToNull(request.getReference()))
                .paidOn(payment ? null : request.getPaidOn())
                .note(OptionalText.blankToNull(request.getNote()))
                .status(SupplierPaymentStatus.PENDING_APPROVAL)
                .recordedBy(actor.getId())
                .recordedAt(clock.instant())
                .build());
        event(saved, payment ? SupplierPaymentEventType.REQUESTED : SupplierPaymentEventType.RECORDED, actor.getId(),
                saved.getReference(), saved.getPaidOn(), saved.getNote(), null);
        askForConfirmation(order, saved, actor);
        return toEntry(saved, order);
    }

    /** The requester corrects an entry that still waits for a decision. */
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
        boolean isPayment = payment.getKind() == SupplierPaymentKind.PAYMENT;
        PaymentMilestone milestone = milestoneFor(payment.getKind(), request.getMilestone());
        if (!isPayment) {
            ensureRefundDetails(order, request);
        }
        BigDecimal amount = allowedAmount(figures(order), payment.getKind(), milestone, request.getAmount(), payment);
        ensureSupplierCanConfirm(order, payment.getKind());

        payment.setMilestone(milestone);
        payment.setAmount(amount);
        payment.setMethod(request.getMethod());
        payment.setReference(isPayment ? null : OptionalText.blankToNull(request.getReference()));
        payment.setPaidOn(isPayment ? null : request.getPaidOn());
        payment.setNote(OptionalText.blankToNull(request.getNote()));
        payment.setRecordedAt(clock.instant());
        SupplierPayment saved = paymentRepository.save(payment);
        event(saved, SupplierPaymentEventType.CORRECTED, actor.getId(), saved.getReference(), saved.getPaidOn(), saved.getNote(), null);
        return toEntry(saved, order);
    }

    /**
     * The requester takes back an entry while it waits on TinQa: a refund or reversal waiting for a decision, or a
     * payment that is requested, approved but not yet paid, or that the supplier says did not arrive.
     */
    @Transactional
    public SupplierPaymentDTOs.Entry withdraw(Long paymentId, User actor) {
        Order order = lockOrder(orderIdOf(paymentId));
        SupplierPayment payment = lockPayment(paymentId);
        boolean askedOfSupplier = payment.getKind() == SupplierPaymentKind.REVERSAL && payment.getStatus() == SupplierPaymentStatus.REPAYMENT_DUE;
        if (!askedOfSupplier && (payment.getKind() != SupplierPaymentKind.PAYMENT || !WITHDRAWABLE_PAYMENT.contains(payment.getStatus()))) {
            ensurePending(payment);
        }
        ensureRecorder(payment, actor, "withdraw");
        payment.setStatus(SupplierPaymentStatus.WITHDRAWN);
        payment.setDecidedBy(actor.getId());
        payment.setDecidedAt(clock.instant());
        SupplierPayment saved = paymentRepository.save(payment);
        event(saved, SupplierPaymentEventType.WITHDRAWN, actor.getId(), null, null, null, null);
        if (askedOfSupplier) {
            supplierLoginService.activeUserIdOf(order.getSupplier().getId()).ifPresent(supplierUser -> notificationService.createForUser(
                    supplierUser, "Pay-back request withdrawn for order " + order.getOrderNumber(), describe(saved) + " for order "
                            + order.getOrderNumber() + " is no longer asked of you.", NotificationLinks.SUPPLIER_ORDERS));
        }
        return toEntry(saved, order);
    }

    /**
     * Undoes a confirmed payment or refund with a reversal of the same amount. A payment's reversal is money the supplier
     * pays back: it goes straight to the supplier (REPAYMENT_DUE), who pays it and gives its reference, and a Finance
     * Manager checks it arrived; no Super Admin step. A refund's reversal is money going back out to the supplier and is
     * decided by a Super Admin.
     */
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
        if (figures(order).reversalClosed()) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_REVERSAL_CLOSED, "Order " + order.getOrderNumber()
                    + " is delivered, tested and fully paid, and the supplier confirmed every payment: its payments can no longer be reversed.");
        }
        boolean payBack = original.getKind() == SupplierPaymentKind.PAYMENT;
        Long supplierUser = payBack ? requireSupplierLogin(order) : null;
        SupplierPayment saved = paymentRepository.save(SupplierPayment.builder()
                .orderId(order.getId())
                .kind(SupplierPaymentKind.REVERSAL)
                // A payment's milestone: it comes off that part. A refund's reversal has none
                .milestone(payBack ? original.getMilestone() : null)
                .amount(original.getAmount())
                .chargeAmount(payBack ? ZERO : original.getChargeAmount())
                // Paid back: the supplier chooses how, and gives its reference and date when they pay
                .method(original.getMethod())
                .reference(payBack ? null : original.getReference())
                .paidOn(payBack ? null : LocalDate.now(clock))
                .note(request.getReason().trim())
                .reversesId(original.getId())
                .status(payBack ? SupplierPaymentStatus.REPAYMENT_DUE : SupplierPaymentStatus.PENDING_APPROVAL)
                .recordedBy(actor.getId())
                .recordedAt(clock.instant())
                .build());
        if (!payBack) {
            event(saved, SupplierPaymentEventType.RECORDED, actor.getId(), saved.getReference(), saved.getPaidOn(), saved.getNote(), null);
            askForConfirmation(order, saved, actor);
            return toEntry(saved, order);
        }
        event(saved, SupplierPaymentEventType.REPAYMENT_REQUESTED, actor.getId(), null, null, saved.getNote(), null);
        notificationService.createForUser(supplierUser, "Pay back a payment for order " + order.getOrderNumber(),
                "TinQa asks you to pay back " + describe(original) + " for order " + order.getOrderNumber() + ": " + saved.getNote()
                        + ". Open the order, pay it back and give the UTR or transaction ID.", NotificationLinks.SUPPLIER_ORDERS);
        return toEntry(saved, order);
    }

    // ------------------------------------------------------------------ money back from the supplier

    /**
     * The supplier pays back what is asked of them (a reversal, or a return's repayment), or pays it again after a
     * Finance Manager said it did not arrive: how, its reference (unless cash), the date, and maybe a receipt. A Finance
     * Manager then checks it. The caller has checked (and locked) that {@code order} is the supplier's own.
     */
    @Transactional
    public SupplierPaymentDTOs.Entry supplierRepay(Order order, Long paymentId, SupplierPaymentDTOs.RepaymentRequest request,
                                                  MultipartFile receipt, User supplierUser) {
        SupplierPayment payment = lockPayment(paymentId);
        if (!payment.getOrderId().equals(order.getId()) || !payment.repayment()) {
            throw paymentNotFound(paymentId);
        }
        if (!REPAYMENT_OPEN.contains(payment.getStatus())) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_PENDING, payment.getStatus() == SupplierPaymentStatus.REPAYMENT_SENT
                    ? "You already paid this back; TinQa's Finance Manager is checking it." : "Nothing is asked of you for this entry.");
        }
        validateRepayment(order, request);
        boolean again = payment.getStatus() == SupplierPaymentStatus.REPAYMENT_MISSING;
        Document document = receipt == null || receipt.isEmpty() ? null : storeProof(order, payment, receipt, supplierUser, DocumentUploaderType.SUPPLIER);
        payment.setMethod(request.getMethod());
        payment.setReference(OptionalText.blankToNull(request.getReference()));
        payment.setPaidOn(request.getPaidOn());
        payment.setSupplierRespondedAt(clock.instant());
        payment.setStatus(SupplierPaymentStatus.REPAYMENT_SENT);
        SupplierPayment saved = paymentRepository.saveAndFlush(payment);
        event(saved, again ? SupplierPaymentEventType.REPAYMENT_RESENT : SupplierPaymentEventType.REPAYMENT_SENT, supplierUser.getId(),
                saved.getReference(), saved.getPaidOn(), OptionalText.blankToNull(request.getNote()), document != null ? document.getId() : null);
        notifyRepaymentSent(order, saved, again);
        return toEntries(List.of(saved), Map.of(order.getId(), order), false).getFirst();
    }

    /**
     * The supplier pays back (part of) the refund they owe on the order's claim, on their own. At most what is still owed,
     * less what they already paid back and is being checked. A Finance Manager then checks it.
     */
    @Transactional
    public SupplierPaymentDTOs.Entry supplierRefund(Order order, SupplierPaymentDTOs.RepaymentRequest request, MultipartFile receipt,
                                                   User supplierUser) {
        if (request.getAmount() == null) {
            throw ApiErrors.fieldError("amount", "Amount is required");
        }
        validateRepayment(order, request);
        ensureRefundAllowed(figures(order), request.getAmount(), ZERO);
        SupplierPayment saved = paymentRepository.save(SupplierPayment.builder()
                .orderId(order.getId())
                .kind(SupplierPaymentKind.REFUND)
                .amount(request.getAmount())
                .method(request.getMethod())
                .reference(OptionalText.blankToNull(request.getReference()))
                .paidOn(request.getPaidOn())
                .status(SupplierPaymentStatus.REPAYMENT_SENT)
                .recordedBy(supplierUser.getId())
                .recordedAt(clock.instant())
                .supplierRespondedAt(clock.instant())
                .build());
        Document document = receipt == null || receipt.isEmpty() ? null : storeProof(order, saved, receipt, supplierUser, DocumentUploaderType.SUPPLIER);
        event(saved, SupplierPaymentEventType.REPAYMENT_SENT, supplierUser.getId(), saved.getReference(), saved.getPaidOn(),
                OptionalText.blankToNull(request.getNote()), document != null ? document.getId() : null);
        notifyRepaymentSent(order, saved, false);
        return toEntries(List.of(saved), Map.of(order.getId(), order), false).getFirst();
    }

    /**
     * A Finance Manager checks money a supplier says they paid back. Arrived: it counts now (the claim is reconciled, a
     * return is settled) and goes to the company account's supplier money. Not arrived: back to the supplier with the
     * reason, to pay or prove it again.
     */
    @Transactional
    public SupplierPaymentDTOs.Entry checkRepayment(Long paymentId, SupplierPaymentDTOs.RepaymentCheckRequest request, User actor) {
        Order order = lockOrder(orderIdOf(paymentId));
        SupplierPayment payment = lockPayment(paymentId);
        if (payment.getStatus() != SupplierPaymentStatus.REPAYMENT_SENT) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_PENDING, REPAYMENT_OPEN.contains(payment.getStatus())
                    ? "The supplier has not paid this back yet." : "Only money the supplier says they paid back is checked (this entry is "
                    + statusText(payment) + ").");
        }
        boolean received = Boolean.TRUE.equals(request.getReceived());
        String reason = OptionalText.blankToNull(request.getReason());
        if (!received && reason == null) {
            throw ApiErrors.fieldError("reason", "Say what is wrong (for example, no credit with this UTR in the bank statement).");
        }
        // Money that arrived is confirmed whatever the claim says now; reconcile settles the claim
        payment.setStatus(received ? SupplierPaymentStatus.APPROVED : SupplierPaymentStatus.REPAYMENT_MISSING);
        payment.setDecidedBy(actor.getId());
        payment.setDecidedAt(clock.instant());
        payment.setDecisionReason(reason);
        SupplierPayment saved = paymentRepository.saveAndFlush(payment);
        event(saved, received ? SupplierPaymentEventType.REPAYMENT_RECEIVED : SupplierPaymentEventType.REPAYMENT_MISSING,
                actor.getId(), null, null, reason, null);
        if (received) {
            reconcile(order);
            if (saved.getReturnId() != null) {
                settleReturn(saved.getReturnId(), actor.getId());
            }
        }
        String supplierMessage = describe(saved) + " for order " + order.getOrderNumber() + (received
                ? ": TinQa received it. Thank you." : " was not found: " + reason + ". Check it and send it again, with a receipt if you have one.");
        Long supplierUser = supplierLoginService.activeUserIdOf(order.getSupplier().getId()).orElse(null);
        if (supplierUser != null) {
            notificationService.createForUser(supplierUser, received ? "TinQa received your payment" : "TinQa did not receive your payment",
                    supplierMessage, NotificationLinks.SUPPLIER_ORDERS);
        }
        // Whoever asked for it (a reversal, a return) is told; a refund the supplier offered on their own has no asker
        if (!saved.getRecordedBy().equals(actor.getId()) && !saved.getRecordedBy().equals(supplierUser)) {
            notificationService.createForUser(saved.getRecordedBy(), received ? "Supplier paid back" : "Supplier repayment not received",
                    describe(saved) + " for order " + order.getOrderNumber() + (received ? " arrived." : " was not found: " + reason),
                    NotificationLinks.ORDERS);
        }
        return toEntry(saved, order);
    }

    /**
     * A return's pieces reached the supplier: they owe what TinQa paid for those pieces (within what the claim still
     * owes, less refunds on their way) and the return shipping charge. Returns the ask, or null when nothing is owed.
     * The caller holds the order's lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayment askReturnRepayment(Order order, Long returnId, BigDecimal piecesValue, BigDecimal shippingCharge,
                                             String why, Long askedBy) {
        Figures figures = figures(order);
        SupplierRefundClaim claim = claimRepository.findByOrderId(order.getId()).orElse(null);
        BigDecimal claimLeft = claim == null || claim.getStatus() != RefundClaimStatus.OPEN ? ZERO
                : claim.getAgreedAmount().subtract(figures.refunded()).subtract(figures.pendingRefunds()).max(ZERO);
        BigDecimal pieces = piecesValue.min(claimLeft).max(ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal charge = Objects.requireNonNullElse(shippingCharge, ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
        if (pieces.signum() == 0 && charge.signum() == 0) {
            return null;
        }
        SupplierPayment saved = paymentRepository.save(SupplierPayment.builder()
                .orderId(order.getId())
                .kind(SupplierPaymentKind.REFUND)
                .amount(pieces)
                .chargeAmount(charge)
                .method(Objects.requireNonNullElse(order.getExpectedPaymentMethod(), PaymentMethod.BANK_TRANSFER))
                .note(why)
                .returnId(returnId)
                .status(SupplierPaymentStatus.REPAYMENT_DUE)
                .recordedBy(askedBy)
                .recordedAt(clock.instant())
                .build());
        event(saved, SupplierPaymentEventType.REPAYMENT_REQUESTED, null, null, null, why, null);
        supplierLoginService.activeUserIdOf(order.getSupplier().getId()).ifPresent(supplierUser -> notificationService.createForUser(supplierUser,
                "Pay back the returned pieces of order " + order.getOrderNumber(), "You owe " + rupees(saved.totalDue()) + " for the pieces "
                        + "returned from order " + order.getOrderNumber() + (charge.signum() > 0 ? " (" + rupees(pieces) + " for the pieces and "
                        + rupees(charge) + " return shipping)" : "") + ". Open the order, pay it and give the UTR or transaction ID.",
                NotificationLinks.SUPPLIER_ORDERS));
        return saved;
    }

    // A return's repayment arrived: the return is settled (its last step)
    private void settleReturn(Long returnId, Long actorId) {
        returnRepository.findByIdForUpdate(returnId).ifPresent(found -> {
            if (found.getStatus() != com.tinqa.procurement.order.supplierreturn.SupplierReturnStatus.RECEIVED_BY_SUPPLIER) {
                return;
            }
            found.setStatus(com.tinqa.procurement.order.supplierreturn.SupplierReturnStatus.SETTLED);
            found.setSettledAt(clock.instant());
            returnRepository.save(found);
            returnEventRepository.save(com.tinqa.procurement.order.supplierreturn.SupplierReturnEvent.builder()
                    .returnId(found.getId())
                    .orderId(found.getOrderId())
                    .type(com.tinqa.procurement.order.supplierreturn.SupplierReturnEventType.SETTLED)
                    .actorId(actorId)
                    .at(clock.instant())
                    .note("The supplier's repayment arrived")
                    .build());
        });
    }

    /** Confirmed repayments for the company account (GET /v1/internal/finance/supplier-receipts), oldest first. */
    @Transactional(readOnly = true)
    public SupplierPaymentDTOs.ReceiptPage receiptsSince(java.time.Instant since, int limit) {
        int size = Math.clamp(limit, 1, 500);
        List<SupplierPayment> found = paymentRepository.findReceiptsSince(since == null ? java.time.Instant.EPOCH : since,
                org.springframework.data.domain.PageRequest.of(0, size + 1));
        boolean hasMore = found.size() > size;
        List<SupplierPayment> page = hasMore ? found.subList(0, size) : found;
        if (page.isEmpty()) {
            return new SupplierPaymentDTOs.ReceiptPage(List.of(), false);
        }
        Map<Long, Order> orders = orderRepository.findAllById(page.stream().map(SupplierPayment::getOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        Map<Long, SupplierPayment> reversed = paymentRepository.findAllById(page.stream().map(SupplierPayment::getReversesId)
                .filter(Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(SupplierPayment::getId, Function.identity()));
        Map<Long, SupplierRefundClaim> claims = claimRepository.findByOrderIdIn(orders.keySet()).stream()
                .collect(Collectors.toMap(SupplierRefundClaim::getOrderId, Function.identity()));
        Map<Long, com.tinqa.procurement.order.supplierreturn.SupplierReturn> returns = returnRepository.findAllById(page.stream()
                .map(SupplierPayment::getReturnId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(com.tinqa.procurement.order.supplierreturn.SupplierReturn::getId, Function.identity()));
        Map<Long, List<SupplierPaymentEvent>> events = eventRepository.findByPaymentIdInOrderByAtAscIdAsc(page.stream().map(SupplierPayment::getId).toList())
                .stream().collect(Collectors.groupingBy(SupplierPaymentEvent::getPaymentId));
        Map<Long, String> proofNames = proofNames(events.values().stream().flatMap(List::stream).toList());
        Map<Long, User> users = users(page);
        return new SupplierPaymentDTOs.ReceiptPage(page.stream().map(payment -> {
            Order order = orders.get(payment.getOrderId());
            SupplierPayment original = payment.getReversesId() != null ? reversed.get(payment.getReversesId()) : null;
            com.tinqa.procurement.order.supplierreturn.SupplierReturn supplierReturn = payment.getReturnId() != null ? returns.get(payment.getReturnId()) : null;
            String reason = payment.getKind() == SupplierPaymentKind.REVERSAL
                    ? (payment.getMilestone() != null ? "PAYMENT_REVERSAL" : "REFUND_REVERSAL")
                    : (payment.getReturnId() != null || (original != null && original.getReturnId() != null) ? "FAILED_PIECES_RETURN" : "REFUND_CLAIM");
            SupplierRefundClaim claim = claims.get(payment.getOrderId());
            String receiptFile = events.getOrDefault(payment.getId(), List.of()).stream()
                    .filter(event -> event.getProofDocumentId() != null).reduce((first, second) -> second)
                    .map(event -> proofNames.get(event.getProofDocumentId())).orElse(null);
            String note = payment.getKind() == SupplierPaymentKind.REVERSAL ? payment.getNote()
                    : supplierReturn != null ? supplierReturn.getReason() : payment.getNote();
            return SupplierPaymentDTOs.Receipt.builder()
                    .receiptId(payment.getId())
                    .postingKey("PROCUREMENT_SUPPLIER_RECEIPT:" + payment.getId())
                    .kind(payment.getKind())
                    // A refund's reversal is money going back out to the supplier
                    .direction(payment.repayment() ? "IN" : "OUT")
                    .reason(reason)
                    .claimCause(reason.equals("REFUND_CLAIM") || reason.equals("FAILED_PIECES_RETURN") || reason.equals("REFUND_REVERSAL")
                            ? (claim != null ? claim.getCause().name() : null) : null)
                    .orderNumber(order != null ? order.getOrderNumber() : null)
                    .supplierName(order != null ? order.getSupplier().getName() : null)
                    .returnId(payment.getReturnId() != null ? payment.getReturnId() : original != null ? original.getReturnId() : null)
                    .milestone(payment.getMilestone())
                    .amount(payment.getAmount())
                    .chargeAmount(payment.getChargeAmount())
                    .method(payment.getMethod())
                    .reference(payment.getReference())
                    .paidOn(payment.getPaidOn())
                    .note(note)
                    .receiptFileName(receiptFile)
                    .confirmedBy(usernameOf(users, payment.getDecidedBy()))
                    .confirmedAt(payment.getDecidedAt())
                    .build();
        }).toList(), hasMore);
    }

    // ------------------------------------------------------------------ deciding (checker)

    /**
     * Someone other than the requester decides an entry; only a Super Admin holds SUPPLIER_PAYMENT_APPROVE. A payment
     * request, approved, is re-checked against the order as it is now and goes to the Finance Managers to pay (TO_PAY):
     * no money has moved yet. A refund or reversal, approved, counts at once.
     */
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
            ensureStillAllowed(figures(order), payment);
        }
        boolean isRequest = payment.getKind() == SupplierPaymentKind.PAYMENT;
        if (approved && isRequest) {
            ensureSupplierCanConfirm(order, payment.getKind());
            PaymentMilestone first = earlierToApprove(figures(order), payment.getMilestone());
            if (first != null) {
                throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_OUT_OF_ORDER, "Approve the " + label(first).toLowerCase()
                        + " payment of order " + order.getOrderNumber() + " first: the " + label(payment.getMilestone()).toLowerCase()
                        + " payment is approved only after it" + (figures(order).requestedBy().get(first).signum() > 0
                        ? " (its request waits for your approval)." : " (it has not been requested yet)."));
            }
        }

        payment.setStatus(!approved ? SupplierPaymentStatus.REJECTED
                : isRequest ? SupplierPaymentStatus.TO_PAY : SupplierPaymentStatus.APPROVED);
        payment.setDecidedBy(approver.getId());
        payment.setDecidedAt(clock.instant());
        payment.setDecisionReason(reason);
        SupplierPayment saved = paymentRepository.saveAndFlush(payment);
        event(saved, isRequest ? (approved ? SupplierPaymentEventType.REQUEST_APPROVED : SupplierPaymentEventType.REQUEST_REJECTED)
                : (approved ? SupplierPaymentEventType.APPROVED : SupplierPaymentEventType.REJECTED), approver.getId(), null, null, reason, null);

        if (isRequest) {
            notificationService.createForUser(payment.getRecordedBy(), approved ? "Payment request approved" : "Payment request rejected",
                    describe(saved) + " for order " + order.getOrderNumber() + " was " + (approved
                            ? "approved. Pay the supplier and record the payment's reference." : "rejected; nothing will be paid.")
                            + (reason != null ? " Reason: " + reason : ""), NotificationLinks.ORDERS);
            if (approved) {
                notificationService.createForPermission(Permission.SUPPLIER_PAYMENT_RECORD, payment.getRecordedBy(), "Supplier payment to make",
                        describe(saved) + " for order " + order.getOrderNumber() + " (" + order.getSupplier().getName()
                                + ") was approved. Pay it and record its reference.", NotificationLinks.APPROVALS);
            }
            return toEntry(saved, order);
        }
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

    /**
     * A Finance Manager made an approved payment: its reference (unless cash) and date. It now waits for the supplier to
     * confirm they received it.
     */
    @Transactional
    public SupplierPaymentDTOs.Entry markPaid(Long paymentId, SupplierPaymentDTOs.PaidRequest request, User actor) {
        Order order = lockOrder(orderIdOf(paymentId));
        SupplierPayment payment = lockPayment(paymentId);
        if (payment.getKind() != SupplierPaymentKind.PAYMENT || payment.getStatus() != SupplierPaymentStatus.TO_PAY) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_PENDING, payment.getStatus() == SupplierPaymentStatus.PENDING_APPROVAL
                    ? "This payment request waits for a Super Admin's approval: nothing can be paid before that."
                    : "Only an approved payment request can be paid (this one is " + statusText(payment) + ").");
        }
        if (figures(order).closed()) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_DUE, "Order " + order.getOrderNumber() + " is "
                    + order.getOrderStatus().label() + ": nothing more is paid for it. Withdraw this request.");
        }
        Figures paying = figures(order);
        for (PaymentMilestone earlier : PaymentMilestone.values()) {
            if (earlier.ordinal() < payment.getMilestone().ordinal() && paying.toPayBy().get(earlier).signum() > 0) {
                throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_OUT_OF_ORDER, "Pay the " + label(earlier).toLowerCase()
                        + " payment of order " + order.getOrderNumber() + " first: the parts of the plan are paid in order.");
            }
        }
        ensurePaymentDetails(order, payment.getMethod(), request.getReference(), request.getPaidOn());
        Long supplierUser = ensureSupplierCanConfirm(order, payment.getKind());

        payment.setReference(OptionalText.blankToNull(request.getReference()));
        payment.setPaidOn(request.getPaidOn());
        payment.setPaidBy(actor.getId());
        payment.setPaidAt(clock.instant());
        payment.setStatus(SupplierPaymentStatus.AWAITING_SUPPLIER);
        SupplierPayment saved = paymentRepository.saveAndFlush(payment);
        event(saved, SupplierPaymentEventType.PAID, actor.getId(), saved.getReference(), saved.getPaidOn(),
                OptionalText.blankToNull(request.getNote()), null);
        notifySent(order, saved, supplierUser, false);
        return toEntry(saved, order);
    }

    /**
     * The supplier said a payment did not arrive: a Finance Manager re-checked it and sends it again, saying what they
     * found, optionally with a proof file (a bank statement, a screenshot). It waits for the supplier again.
     */
    @Transactional
    public SupplierPaymentDTOs.Entry resend(Long paymentId, String reference, LocalDate paidOn, String note, MultipartFile proof, User actor) {
        Order order = lockOrder(orderIdOf(paymentId));
        SupplierPayment payment = lockPayment(paymentId);
        if (payment.getStatus() != SupplierPaymentStatus.NOT_RECEIVED) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_PENDING,
                    "Only a payment the supplier says did not arrive is sent again (this one is " + statusText(payment) + ").");
        }
        String checked = OptionalText.blankToNull(note);
        if (checked == null) {
            throw ApiErrors.fieldError("note", "Say what you checked (for example, the bank confirms the transfer reached the supplier's account).");
        }
        if (checked.length() > 500) {
            throw ApiErrors.fieldError("note", "Note cannot exceed 500 characters");
        }
        if (reference != null && reference.trim().length() > 100) {
            throw ApiErrors.fieldError("reference", "Reference cannot exceed 100 characters");
        }
        if (paidOn == null) {
            throw ApiErrors.fieldError("paidOn", "Payment date is required");
        }
        ensurePaymentDetails(order, payment.getMethod(), reference, paidOn);
        Long supplierUser = ensureSupplierCanConfirm(order, payment.getKind());
        Document document = proof == null || proof.isEmpty() ? null : storeProof(order, payment, proof, actor);

        payment.setReference(OptionalText.blankToNull(reference));
        payment.setPaidOn(paidOn);
        payment.setPaidBy(actor.getId());
        payment.setPaidAt(clock.instant());
        payment.setStatus(SupplierPaymentStatus.AWAITING_SUPPLIER);
        payment.setSupplierResponseReason(null);
        SupplierPayment saved = paymentRepository.saveAndFlush(payment);
        event(saved, SupplierPaymentEventType.RESENT, actor.getId(), saved.getReference(), saved.getPaidOn(), checked,
                document != null ? document.getId() : null);
        notifySent(order, saved, supplierUser, true);
        return toEntry(saved, order);
    }

    /**
     * The supplier says whether they received a payment waiting for them (any method). Received: it counts as paid now.
     * Not received: it goes back to the Finance Managers (NOT_RECEIVED) to re-check and send again, and the requester
     * and whoever paid it are told. The caller has checked (and locked) that {@code order} is the supplier's own.
     */
    @Transactional
    public SupplierPaymentDTOs.Entry supplierReceipt(Order order, Long paymentId, SupplierPaymentDTOs.ReceiptRequest request, User supplierUser) {
        SupplierPayment payment = lockPayment(paymentId);
        if (!payment.getOrderId().equals(order.getId())) {
            throw paymentNotFound(paymentId);
        }
        if (payment.getStatus() != SupplierPaymentStatus.AWAITING_SUPPLIER) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_PENDING, "This payment is not waiting for your confirmation.");
        }
        boolean received = Boolean.TRUE.equals(request.getReceived());
        String reason = OptionalText.blankToNull(request.getReason());
        if (!received && reason == null) {
            throw ApiErrors.fieldError("reason", "Say what is wrong with this payment (for example, the money never reached you).");
        }
        payment.setStatus(received ? SupplierPaymentStatus.APPROVED : SupplierPaymentStatus.NOT_RECEIVED);
        payment.setSupplierRespondedAt(clock.instant());
        payment.setSupplierResponseReason(received ? null : reason);
        SupplierPayment saved = paymentRepository.saveAndFlush(payment);
        event(saved, received ? SupplierPaymentEventType.RECEIVED : SupplierPaymentEventType.NOT_RECEIVED,
                supplierUser != null ? supplierUser.getId() : null, null, null, reason, null);
        if (received) {
            // Whatever happened to the order meanwhile, the money reached the supplier: it counts, and any overpayment is claimed back
            reconcile(order);
        }

        String title = received ? "Supplier confirmed a payment" : "Supplier did not receive a payment";
        String message = describe(saved) + " for order " + order.getOrderNumber() + ": " + order.getSupplier().getName()
                + (received ? " confirmed they received it; it now counts as paid."
                : " says they did not receive it. Check it with the bank and send it again (with a proof if you have one). Reason: " + reason);
        Set<Long> told = Stream.of(saved.getRecordedBy(), saved.getPaidBy()).filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        told.forEach(userId -> notificationService.createForUser(userId, title, message, NotificationLinks.ORDERS));
        if (!received) {
            notificationService.createForPermission(Permission.SUPPLIER_PAYMENT_RECORD, saved.getPaidBy(), "Supplier payment to check",
                    message, NotificationLinks.APPROVALS);
        }
        return toEntries(List.of(saved), Map.of(order.getId(), order), false).getFirst();
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
                           Map<PaymentMilestone, BigDecimal> requestedBy, Map<PaymentMilestone, BigDecimal> toPayBy,
                           BigDecimal refunded, BigDecimal pendingRefunds, BigDecimal awaitingSupplier, boolean reversalOpen) {

        BigDecimal paid() {
            return sum(paidBy.values());
        }

        BigDecimal pendingPayments() {
            return sum(pendingBy.values());
        }

        /**
         * The reversal window is closed: the order was delivered and tested, everything it is worth is paid, and the
         * supplier confirmed receiving every payment (none waits anywhere) with no reversal still open. Its payments are
         * final from then on; money still owed back (failed units) is the refund claim's, never a reversal.
         */
        boolean reversalClosed() {
            return DELIVERED_ON.contains(order.getOrderStatus())
                    && tested
                    && paid().signum() > 0
                    && pendingPayments().signum() == 0
                    && !reversalOpen
                    && paid().subtract(refunded).compareTo(payable) >= 0;
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

        BigDecimal planned(PaymentMilestone milestone) {
            return switch (milestone) {
                case ADVANCE -> plannedAdvance();
                case MIDTERM -> plannedMidterm();
                case FINAL -> plannedFinal();
            };
        }

        /** The most that may have been paid in all, now. */
        BigDecimal cap() {
            if (closed) {
                return ZERO;
            }
            return tested ? payable : plannedAdvance().add(plannedMidterm());
        }

        /** What more may be paid in all now: the cap less what was paid (net of refunds) and what waits. */
        BigDecimal capLeft() {
            return cap().subtract(paid().subtract(refunded)).subtract(pendingPayments());
        }

        /**
         * What a payment against {@code milestone} is now: what is left of that part, within what is left of the cap.
         * {@code own} is the waiting entry being corrected or confirmed: its amount is given back first.
         */
        BigDecimal left(PaymentMilestone milestone, SupplierPayment own) {
            if (!due(milestone)) {
                return ZERO;
            }
            BigDecimal ownAll = own != null && own.getKind() == SupplierPaymentKind.PAYMENT ? own.getAmount() : ZERO;
            BigDecimal ownHere = own != null && own.getKind() == SupplierPaymentKind.PAYMENT && own.getMilestone() == milestone ? own.getAmount() : ZERO;
            BigDecimal part = planned(milestone).subtract(paidBy.get(milestone)).subtract(pendingBy.get(milestone)).add(ownHere);
            return part.min(capLeft().add(ownAll)).max(ZERO);
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
        // Of the waiting payments: those waiting for a Super Admin, and those approved but not yet paid
        Map<PaymentMilestone, BigDecimal> requestedBy = new EnumMap<>(PaymentMilestone.class);
        Map<PaymentMilestone, BigDecimal> toPayBy = new EnumMap<>(PaymentMilestone.class);
        for (PaymentMilestone milestone : PaymentMilestone.values()) {
            paidBy.put(milestone, ZERO);
            pendingBy.put(milestone, ZERO);
            requestedBy.put(milestone, ZERO);
            toPayBy.put(milestone, ZERO);
        }
        BigDecimal refunded = ZERO;
        BigDecimal pendingRefunds = ZERO;
        BigDecimal awaitingSupplier = ZERO;
        boolean reversalOpen = false;
        for (SupplierPaymentRepository.Total total : paymentRepository.totalsForOrder(order.getId(), COUNTED)) {
            BigDecimal amount = total.getTotal();
            boolean approved = total.getStatus() == SupplierPaymentStatus.APPROVED;
            PaymentMilestone milestone = total.getMilestone();
            switch (total.getKind()) {
                case PAYMENT -> {
                    (approved ? paidBy : pendingBy).merge(milestone, amount, BigDecimal::add);
                    if (total.getStatus() == SupplierPaymentStatus.AWAITING_SUPPLIER) awaitingSupplier = awaitingSupplier.add(amount);
                    if (total.getStatus() == SupplierPaymentStatus.PENDING_APPROVAL) requestedBy.merge(milestone, amount, BigDecimal::add);
                    if (total.getStatus() == SupplierPaymentStatus.TO_PAY) toPayBy.merge(milestone, amount, BigDecimal::add);
                }
                case REFUND -> {
                    if (approved) refunded = refunded.add(amount);
                    else pendingRefunds = pendingRefunds.add(amount);
                }
                // Only a confirmed reversal counts; one waiting changes nothing yet
                case REVERSAL -> {
                    if (approved && milestone != null) paidBy.merge(milestone, amount.negate(), BigDecimal::add);
                    else if (approved) refunded = refunded.subtract(amount);
                    else reversalOpen = true;
                }
            }
        }
        BigDecimal passed = tested ? stock.getApprovedUnitsPassed() : BigDecimal.ZERO;
        BigDecimal failed = tested ? Objects.requireNonNullElse(stock.getApprovedDefectedUnits(), BigDecimal.ZERO) : BigDecimal.ZERO;
        return new Figures(order, tested, closed, orderValue, payable, passed, failed, paidBy, pendingBy, requestedBy, toPayBy,
                refunded, pendingRefunds, awaitingSupplier, reversalOpen);
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
                .planned(figures.planned(milestone))
                .paid(figures.paidBy().get(milestone))
                .pending(figures.pendingBy().get(milestone))
                .due(figures.due(milestone))
                .left(figures.left(milestone, null))
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
                .awaitingSupplier(figures.awaitingSupplier())
                .refunded(figures.refunded())
                .pendingRefunds(figures.pendingRefunds())
                .outstanding(figures.payable().subtract(netPaid).max(ZERO))
                .payableNow(payableNow)
                .openMilestones(open)
                .reversalClosed(figures.reversalClosed())
                .milestones(milestones)
                .settlement(figures.tested() && !figures.closed()
                        ? PaymentSettlement.of(order, figures.orderValue(), figures.payable(), figures.unitsPassed(), figures.unitsFailed())
                        : null)
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
     * The amount of a new or corrected entry. A payment pays what is left of its part (see Figures.left): the amount is
     * filled in, and one that was given must match it. A refund is at most what the supplier still owes on the open
     * claim. {@code own} is the waiting entry being corrected (null for a new one).
     */
    private BigDecimal allowedAmount(Figures figures, SupplierPaymentKind kind, PaymentMilestone milestone, BigDecimal requested, SupplierPayment own) {
        Order order = figures.order();
        if (kind == SupplierPaymentKind.REFUND) {
            if (requested == null) {
                throw ApiErrors.fieldError("amount", "Amount is required");
            }
            ensureRefundAllowed(figures, requested, own != null ? own.getAmount() : ZERO);
            return requested;
        }
        if (!figures.openMilestones().contains(milestone)) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_DUE, notDueMessage(figures, milestone));
        }
        BigDecimal left = figures.left(milestone, own);
        if (left.signum() == 0) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_DUE, "Nothing is left to pay on the " + label(milestone).toLowerCase()
                    + " part of order " + order.getOrderNumber() + " now. " + capHint(figures));
        }
        if (requested != null && requested.compareTo(left) != 0) {
            throw ApiErrors.fieldError("amount", "The amount is set by the payment plan: " + rupees(left) + " is left to pay on the "
                    + label(milestone).toLowerCase() + " part of order " + order.getOrderNumber() + " now.");
        }
        return left;
    }

    /** Confirming re-checks the entry against the order as it is now (the plan's figures may have changed since). */
    private void ensureStillAllowed(Figures figures, SupplierPayment payment) {
        if (payment.getKind() == SupplierPaymentKind.REFUND) {
            ensureRefundAllowed(figures, payment.getAmount(), payment.getAmount());
            return;
        }
        if (!figures.openMilestones().contains(payment.getMilestone())) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_DUE, notDueMessage(figures, payment.getMilestone()));
        }
        BigDecimal left = figures.left(payment.getMilestone(), payment);
        if (payment.getAmount().compareTo(left) > 0) {
            throw ApiErrors.fieldError("amount", "Only " + rupees(left) + " is now left to pay on the " + label(payment.getMilestone()).toLowerCase()
                    + " part of order " + figures.order().getOrderNumber() + ": reject this entry and record it again. " + capHint(figures));
        }
    }

    /** A refund fits the open claim. {@code ownPending} is the amount of the refund being corrected or confirmed. */
    private void ensureRefundAllowed(Figures figures, BigDecimal amount, BigDecimal ownPending) {
        Order order = figures.order();
        SupplierRefundClaim claim = claimRepository.findByOrderId(order.getId()).orElse(null);
        if (claim == null || claim.getStatus() != RefundClaimStatus.OPEN) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_REFUND_NOT_DUE, "The supplier owes nothing back for order " + order.getOrderNumber() + ".");
        }
        BigDecimal left = claim.getAgreedAmount().subtract(figures.refunded()).subtract(figures.pendingRefunds()).add(ownPending).max(ZERO);
        if (amount.compareTo(left) > 0) {
            throw ApiErrors.fieldError("amount", "The supplier still owes " + rupees(left) + " (not counting refunds waiting for confirmation).");
        }
    }

    /**
     * A payment to the supplier counts only once the supplier confirms they received it, in their supplier login:
     * without an active one, they can't be paid. Returns the supplier's user id (null for refunds and reversals).
     */
    private Long ensureSupplierCanConfirm(Order order, SupplierPaymentKind kind) {
        if (kind != SupplierPaymentKind.PAYMENT) {
            return null;
        }
        return supplierLoginService.activeUserIdOf(order.getSupplier().getId()).orElseThrow(() -> ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NEEDS_LOGIN,
                "A payment counts only once the supplier confirms they received it in their supplier login, and "
                        + order.getSupplier().getName() + " has no active one. Give them a supplier login first."));
    }

    /**
     * Plan order: the first earlier part of the plan that must be approved before a request for {@code milestone} can be
     * (a request for it still waits for a Super Admin, or something of it was never requested), or null. A part planned
     * at nothing is skipped.
     */
    private static PaymentMilestone earlierToApprove(Figures figures, PaymentMilestone milestone) {
        for (PaymentMilestone earlier : PaymentMilestone.values()) {
            if (earlier.ordinal() >= milestone.ordinal()) {
                return null;
            }
            if (figures.planned(earlier).signum() == 0) {
                continue;
            }
            if (figures.requestedBy().get(earlier).signum() > 0 || figures.left(earlier, null).signum() > 0) {
                return earlier;
            }
        }
        return null;
    }

    /** What the supplier gives when they pay money back: a date (not before the order, not in the future) and a reference unless cash. */
    private void validateRepayment(Order order, SupplierPaymentDTOs.RepaymentRequest request) {
        if (request.getMethod() == null) {
            throw ApiErrors.fieldError("method", "Say how you paid it back");
        }
        if (request.getPaidOn() == null) {
            throw ApiErrors.fieldError("paidOn", "Payment date is required");
        }
        if (request.getReference() != null && request.getReference().trim().length() > 100) {
            throw ApiErrors.fieldError("reference", "Reference cannot exceed 100 characters");
        }
        if (request.getNote() != null && request.getNote().trim().length() > 500) {
            throw ApiErrors.fieldError("note", "Note cannot exceed 500 characters");
        }
        if (request.getAmount() != null && (request.getAmount().signum() <= 0 || request.getAmount().scale() > 2)) {
            throw ApiErrors.fieldError("amount", "Amount must be more than zero, with at most 2 decimal places");
        }
        ensurePaymentDetails(order, request.getMethod(), request.getReference(), request.getPaidOn());
    }

    // Money asked of the supplier is paid back in their supplier login
    private Long requireSupplierLogin(Order order) {
        return supplierLoginService.activeUserIdOf(order.getSupplier().getId()).orElseThrow(() -> ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NEEDS_LOGIN,
                "The supplier pays money back in their supplier login, and " + order.getSupplier().getName()
                        + " has no active one. Give them a supplier login first."));
    }

    // The Finance Managers check the money the supplier says they paid back
    private void notifyRepaymentSent(Order order, SupplierPayment payment, boolean again) {
        notificationService.createForPermission(Permission.SUPPLIER_PAYMENT_RECORD, null, "Supplier repayment to check",
                order.getSupplier().getName() + (again ? " sent again " : " paid back ") + describe(payment) + " for order "
                        + order.getOrderNumber() + " (" + referenceText(payment) + "). Check it arrived.", NotificationLinks.APPROVALS);
    }

    private static String referenceText(SupplierPayment payment) {
        return payment.getReference() != null ? "reference " + payment.getReference() : "cash, paid on " + payment.getPaidOn();
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
            case ADVANCE -> "The advance can be paid once the supplier has accepted order " + order.getOrderNumber() + " (it is "
                    + order.getOrderStatus().label() + ").";
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

    /**
     * Every payment or refund that leaves a trace at a bank says which: the transaction ID of an online payment, the UTR
     * of a bank transfer, the cheque number. Cash has none, so its reference (e.g. a receipt number) stays optional.
     */
    static void ensureReferenceGiven(PaymentMethod method, String reference) {
        if (method == null || method == PaymentMethod.CASH || OptionalText.blankToNull(reference) != null) {
            return;
        }
        throw ApiErrors.fieldError("reference", switch (method) {
            case ONLINE -> "Give the transaction ID of the online payment.";
            case BANK_TRANSFER -> "Give the UTR number of the bank transfer.";
            case CHEQUE -> "Give the cheque number.";
            case CASH -> throw new IllegalStateException();
        });
    }

    /** A refund the supplier paid back says when, and its reference unless it was cash. */
    private static void ensureRefundDetails(Order order, SupplierPaymentDTOs.RecordRequest request) {
        if (request.getPaidOn() == null) {
            throw ApiErrors.fieldError("paidOn", "Payment date is required");
        }
        ensurePaidOnValid(order, request.getPaidOn());
        ensureReferenceGiven(request.getMethod(), request.getReference());
    }

    /** A payment made says when (not before the order, not in the future), and its reference unless it was cash. */
    private void ensurePaymentDetails(Order order, PaymentMethod method, String reference, LocalDate paidOn) {
        if (paidOn.isAfter(LocalDate.now(clock))) {
            throw ApiErrors.fieldError("paidOn", "Payment date cannot be in the future");
        }
        ensurePaidOnValid(order, paidOn);
        ensureReferenceGiven(method, reference);
    }

    private static String statusText(SupplierPayment payment) {
        return payment.getStatus().name().toLowerCase().replace('_', ' ');
    }

    private static void ensurePaidOnValid(Order order, LocalDate paidOn) {
        if (paidOn != null && order.getOrderDate() != null && paidOn.isBefore(order.getOrderDate())) {
            throw ApiErrors.fieldError("paidOn", "The payment date cannot be before the order date (" + order.getOrderDate() + ").");
        }
    }

    private static void ensurePending(SupplierPayment payment) {
        if (payment.getStatus() != SupplierPaymentStatus.PENDING_APPROVAL) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_PAYMENT_NOT_PENDING, "This entry was already " + statusText(payment) + ".");
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
        boolean isRequest = payment.getKind() == SupplierPaymentKind.PAYMENT;
        notificationService.createForPermission(Permission.SUPPLIER_PAYMENT_APPROVE, actor.getId(),
                isRequest ? "Supplier payment request to approve" : "Supplier payment awaiting confirmation",
                describe(payment) + " for order " + order.getOrderNumber() + " (" + order.getSupplier().getName() + ") "
                        + (isRequest ? "is requested. Nothing is paid until you approve it." : "was recorded and waits for someone else to confirm it."),
                NotificationLinks.APPROVALS);
    }

    // The supplier is asked to confirm a payment made to them; the requester is told it was paid
    private void notifySent(Order order, SupplierPayment payment, Long supplierUser, boolean again) {
        notificationService.createForUser(supplierUser, "Confirm the payment for order " + order.getOrderNumber(),
                describe(payment) + " paid on " + payment.getPaidOn() + " was " + (again ? "checked and sent again" : "made")
                        + " for order " + order.getOrderNumber() + ". Open the order and confirm whether you received it.",
                NotificationLinks.SUPPLIER_ORDERS);
        if (!payment.getRecordedBy().equals(payment.getPaidBy())) {
            notificationService.createForUser(payment.getRecordedBy(), "Payment waiting for the supplier",
                    describe(payment) + " for order " + order.getOrderNumber() + " was " + (again ? "sent again" : "paid")
                            + "; it counts once " + order.getSupplier().getName() + " confirms they received it.", NotificationLinks.ORDERS);
        }
    }

    private void event(SupplierPayment payment, SupplierPaymentEventType type, Long actorId, String reference, LocalDate paidOn,
                       String note, Long proofDocumentId) {
        eventRepository.save(SupplierPaymentEvent.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .type(type)
                .actorId(actorId)
                .at(clock.instant())
                .reference(reference)
                .paidOn(paidOn)
                .note(note)
                .proofDocumentId(proofDocumentId)
                .build());
    }

    private Document storeProof(Order order, SupplierPayment payment, MultipartFile file, User actor) {
        return storeProof(order, payment, file, actor, DocumentUploaderType.ADMIN_L1);
    }

    private Document storeProof(Order order, SupplierPayment payment, MultipartFile file, User actor, DocumentUploaderType uploader) {
        if (file.getSize() > MAX_PROOF_BYTES) {
            throw ApiErrors.fieldError(uploader == DocumentUploaderType.SUPPLIER ? "receipt" : "proof", "A file can be at most 5 MB.");
        }
        UploadedFileValidator.ValidatedFile validated = uploadedFileValidator.validate(file, DocumentType.OTHER);
        if (!PROOF_TYPES.contains(validated.contentType())) {
            throw ApiErrors.fieldError(uploader == DocumentUploaderType.SUPPLIER ? "receipt" : "proof", "The file must be a PDF or a screenshot or photo (png, jpg, webp).");
        }
        String storageKey = fileStorageService.storeFile(file, "order/payment-proof");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorageService.deleteFile(storageKey);
                }
            }
        });
        return documentRepository.save(Document.builder()
                .originalFileName(validated.originalFileName())
                .storageKey(storageKey)
                .contentType(validated.contentType())
                .fileSize(file.getSize())
                .uploaderType(uploader)
                .uploadedByUserId(actor.getId())
                .ownerType(DocumentOwnerType.SUPPLIER)
                .ownerId(order.getSupplier().getId())
                .referenceType(DocumentReferenceType.SUPPLIER_PAYMENT)
                .referenceId(payment.getId())
                .category(DocumentCategory.OTHER)
                .purpose(DocumentPurpose.ORDER_DOCUMENT)
                .type(validated.contentType().equals("application/pdf") ? DocumentType.PDF : DocumentType.IMAGE)
                .stage(DocumentStage.GENERAL)
                // Proof sent with a payment, not a document waiting for a document approver
                .status(DocumentStatus.ACTIVE)
                .build());
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
            case REFUND -> payment.getChargeAmount() != null && payment.getChargeAmount().signum() > 0
                    ? "Repayment of " + rupees(payment.totalDue()) + " for returned pieces (" + rupees(payment.getAmount()) + " + "
                    + rupees(payment.getChargeAmount()) + " return shipping)"
                    : "Refund of " + rupees(payment.getAmount()) + " from the supplier";
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
        if (payments.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = withStaff ? users(payments) : Map.of();
        Map<Long, List<SupplierPaymentEvent>> eventsByPayment = eventRepository
                .findByPaymentIdInOrderByAtAscIdAsc(payments.stream().map(SupplierPayment::getId).toList()).stream()
                .collect(Collectors.groupingBy(SupplierPaymentEvent::getPaymentId));
        List<SupplierPaymentEvent> allEvents = eventsByPayment.values().stream().flatMap(List::stream).toList();
        Map<Long, ActorDirectory.Actor> actors = actorDirectory.of(allEvents.stream().map(SupplierPaymentEvent::getActorId).toList());
        Map<Long, String> proofNames = proofNames(allEvents);
        // Approved entries whose reversal is confirmed or waiting, found in the same list (an order's entries) or not at all
        Set<Long> reversed = payments.stream()
                .filter(p -> p.getKind() == SupplierPaymentKind.REVERSAL && COUNTED.contains(p.getStatus()))
                .map(SupplierPayment::getReversesId).collect(Collectors.toSet());
        return payments.stream().map(payment -> {
            Order order = orders.get(payment.getOrderId());
            List<SupplierPaymentEvent> events = eventsByPayment.getOrDefault(payment.getId(), List.of());
            return SupplierPaymentDTOs.Entry.builder()
                    .id(payment.getId())
                    .orderId(payment.getOrderId())
                    .orderNumber(order != null ? order.getOrderNumber() : null)
                    .supplierName(order != null ? order.getSupplier().getName() : null)
                    .kind(payment.getKind())
                    .milestone(payment.getMilestone())
                    .amount(payment.getAmount())
                    .chargeAmount(payment.getChargeAmount())
                    .returnId(payment.getReturnId())
                    .method(payment.getMethod())
                    .reference(payment.getReference())
                    .paidOn(payment.getPaidOn())
                    // A reversal's or return's reason is what the supplier is asked to pay back for
                    .note(withStaff || payment.repayment() ? payment.getNote() : null)
                    .reversesId(payment.getReversesId())
                    .reversed(reversed.contains(payment.getId()))
                    .status(payment.getStatus())
                    .recordedBy(withStaff ? payment.getRecordedBy() : null)
                    .recordedByUsername(usernameOf(users, payment.getRecordedBy()))
                    .recordedAt(payment.getRecordedAt())
                    .decidedByUsername(usernameOf(users, payment.getDecidedBy()))
                    .decidedAt(payment.getDecidedAt())
                    // A Finance Manager's "not received" reason is the supplier's to act on
                    .decisionReason(withStaff || payment.getStatus() == SupplierPaymentStatus.REPAYMENT_MISSING ? payment.getDecisionReason() : null)
                    .supplierRespondedAt(payment.getSupplierRespondedAt())
                    .supplierResponseReason(payment.getSupplierResponseReason())
                    .paidByUsername(usernameOf(users, payment.getPaidBy()))
                    .paidAt(payment.getPaidAt())
                    .sendCount((int) events.stream().filter(e -> e.getType() == SupplierPaymentEventType.PAID
                            || e.getType() == SupplierPaymentEventType.RESENT).count())
                    .events(events.stream().filter(e -> withStaff || e.getType().shownToSupplier())
                            .map(e -> toEvent(e, actors, proofNames, withStaff)).toList())
                    .build();
        }).toList();
    }

    private static SupplierPaymentDTOs.Event toEvent(SupplierPaymentEvent event, Map<Long, ActorDirectory.Actor> actors,
                                                     Map<Long, String> proofNames, boolean withStaff) {
        ActorDirectory.Actor actor = event.getActorId() == null ? null : actors.get(event.getActorId());
        return SupplierPaymentDTOs.Event.builder()
                .id(event.getId())
                .type(event.getType())
                .at(event.getAt())
                .actorRole(actor != null ? actor.role() : null)
                .actorName(withStaff && actor != null && !actor.supplier() ? actor.name() : null)
                .reference(event.getReference())
                .paidOn(event.getPaidOn())
                // What the requester or approver wrote stays with staff, except what was sent with a payment
                .note(withStaff || event.getType() == SupplierPaymentEventType.RESENT || event.getType() == SupplierPaymentEventType.PAID
                        || event.getType() == SupplierPaymentEventType.NOT_RECEIVED || event.getType().repayment() ? event.getNote() : null)
                .proofDocumentId(event.getProofDocumentId())
                .proofFileName(event.getProofDocumentId() != null ? proofNames.get(event.getProofDocumentId()) : null)
                .build();
    }

    private Map<Long, String> proofNames(Collection<SupplierPaymentEvent> events) {
        List<Long> ids = events.stream().map(SupplierPaymentEvent::getProofDocumentId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        documentRepository.findAllById(ids).forEach(document -> names.put(document.getId(), document.getOriginalFileName()));
        return names;
    }

    private Map<Long, User> users(Collection<SupplierPayment> payments) {
        Set<Long> ids = payments.stream().flatMap(p -> Stream.of(p.getRecordedBy(), p.getDecidedBy(), p.getPaidBy()))
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
