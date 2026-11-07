package com.tinqa.procurement.order.supplierreturn;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.employee.service.ActorDirectory;
import com.tinqa.procurement.finance.service.PurchasePostingRecorder;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.payment.SupplierPayment;
import com.tinqa.procurement.order.payment.SupplierPaymentRepository;
import com.tinqa.procurement.order.payment.SupplierPaymentService;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.repository.StockRepository;
import com.tinqa.procurement.supplier.login.SupplierLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Returns of pieces that failed testing to their supplier, the way marketplaces handle a vendor return (RTV):
 * <ol>
 *     <li>A Supplier Manager raises the return once the order's stock entry is approved with failed units: how many
 *     pieces, why, which ones, and whether TinQa ships them or the supplier collects them.</li>
 *     <li>A Super Admin approves it (never the raiser) before anything goes to the supplier, or rejects it.</li>
 *     <li>The supplier accepts it, or declines it with a reason (the Supplier Manager may send it again).</li>
 *     <li>The Supplier Manager records the dispatch: courier, tracking number and shipping charge, or the pickup date.</li>
 *     <li>The supplier confirms the pieces arrived. They then owe what TinQa paid for those pieces (within the refund
 *     claim) and the return shipping: SupplierPaymentService asks it of them, they pay it with its reference, and a
 *     Finance Manager confirms it arrived, which settles the return.</li>
 * </ol>
 * The Super Admin approves only the return itself and follows the rest in the order's flow history. Every step is kept
 * in supplier_return_event in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class SupplierReturnService {

    private static final Set<OrderStatus> CLOSED = EnumSet.of(OrderStatus.CANCELLED, OrderStatus.REFUND_COMPLETED);
    private static final Set<SupplierReturnStatus> CANCELLABLE = EnumSet.of(SupplierReturnStatus.PENDING_APPROVAL,
            SupplierReturnStatus.AWAITING_SUPPLIER, SupplierReturnStatus.DECLINED, SupplierReturnStatus.ACCEPTED);

    private final SupplierReturnRepository returnRepository;
    private final SupplierReturnEventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final UserRepository userRepository;
    private final SupplierPaymentService paymentService;
    private final SupplierPaymentRepository paymentRepository;
    private final SupplierLoginService supplierLoginService;
    private final NotificationService notificationService;
    private final ActorDirectory actorDirectory;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    /** An order's returns and how many failed pieces are left to return, as staff see them. */
    @Transactional(readOnly = true)
    public SupplierReturnDTOs.OrderReturns forOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> orderNotFound(orderId));
        return orderReturns(order, returnRepository.findByOrderIdOrderByRequestedAtDescIdDesc(orderId), true);
    }

    /** The same for the order's supplier: returns from the Super Admin's approval on, no staff names. */
    @Transactional(readOnly = true)
    public SupplierReturnDTOs.OrderReturns forSupplier(Order order) {
        List<SupplierReturn> shown = returnRepository.findByOrderIdOrderByRequestedAtDescIdDesc(order.getId()).stream()
                .filter(found -> found.getStatus().shownToSupplier()
                        && !(found.getStatus() == SupplierReturnStatus.CANCELLED && found.getDecidedAt() == null))
                .toList();
        return orderReturns(order, shown, false);
    }

    /** Returns waiting for a Super Admin, oldest first (the Approvals Hub queue). */
    @Transactional(readOnly = true)
    public List<SupplierReturnDTOs.Return> waiting() {
        List<SupplierReturn> waiting = returnRepository.findByStatusOrderByRequestedAtAscIdAsc(SupplierReturnStatus.PENDING_APPROVAL);
        Map<Long, Order> orders = orderRepository.findAllById(waiting.stream().map(SupplierReturn::getOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        return toDtos(waiting, orders, true);
    }

    /** Per order, the returns waiting for the supplier (to accept, or to confirm the pieces arrived); one statement. */
    @Transactional(readOnly = true)
    public Map<Long, Long> toAnswer(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return returnRepository.countToAnswer(orderIds).stream()
                .collect(Collectors.toMap(SupplierReturnRepository.OrderCount::getOrderId, SupplierReturnRepository.OrderCount::getCount));
    }

    /** The order a return belongs to (the supplier portal checks that it is the supplier's own). */
    @Transactional(readOnly = true)
    public Long orderIdOfReturn(Long returnId) {
        return returnRepository.findById(returnId).map(SupplierReturn::getOrderId).orElseThrow(() -> returnNotFound(returnId));
    }

    // ------------------------------------------------------------------ staff steps

    @Transactional
    public SupplierReturnDTOs.Return raise(Long orderId, SupplierReturnDTOs.RaiseRequest request, User actor) {
        Order order = lockOrder(orderId);
        Stock stock = testedStock(order);
        BigDecimal failed = failedUnits(stock);
        if (stock == null || failed.signum() <= 0) {
            throw notAllowed("Order " + order.getOrderNumber() + " has no failed pieces: a return is raised once its stock entry is "
                    + "approved with units that failed testing.");
        }
        if (CLOSED.contains(order.getOrderStatus())) {
            throw notAllowed("Order " + order.getOrderNumber() + " is " + order.getOrderStatus().label() + ".");
        }
        BigDecimal left = failed.subtract(returnRepository.unitsHeld(order.getId())).max(BigDecimal.ZERO);
        if (request.getUnits().compareTo(left) > 0) {
            throw ApiErrors.fieldError("units", left.signum() == 0 ? "Every failed piece of order " + order.getOrderNumber() + " is already in a return."
                    : "At most " + plain(left) + " failed piece(s) of order " + order.getOrderNumber() + " are left to return.");
        }
        if (supplierLoginService.activeUserIdOf(order.getSupplier().getId()).isEmpty()) {
            throw notAllowed("The supplier accepts a return in their supplier login, and " + order.getSupplier().getName()
                    + " has no active one. Give them a supplier login first.");
        }
        BigDecimal value = order.getUnitPrice().multiply(request.getUnits()).setScale(2, RoundingMode.HALF_UP);
        SupplierReturn saved = returnRepository.save(SupplierReturn.builder()
                .orderId(order.getId())
                .stockId(stock.getId())
                .units(request.getUnits())
                .reason(request.getReason().trim())
                .pieceDetails(OptionalText.blankToNull(request.getPieceDetails()))
                .shippingMode(request.getShippingMode())
                .status(SupplierReturnStatus.PENDING_APPROVAL)
                .piecesValue(value.add(PurchasePostingRecorder.gstOn(order, value).total()))
                .requestedBy(actor.getId())
                .requestedAt(clock.instant())
                .build());
        event(saved, SupplierReturnEventType.REQUESTED, actor.getId(), saved.getReason());
        notificationService.createForPermission(Permission.SUPPLIER_RETURN_APPROVE, actor.getId(), "Return of failed pieces to approve",
                describe(saved, order) + " is raised. Nothing goes to the supplier until you approve it.", NotificationLinks.APPROVALS);
        return toDto(saved, order);
    }

    /** A Super Admin (never the raiser) approves a return, which then goes to the supplier, or rejects it. */
    @Transactional
    public SupplierReturnDTOs.Return decide(Long returnId, SupplierReturnDTOs.DecisionRequest request, User approver) {
        Order order = lockOrder(orderIdOfReturn(returnId));
        SupplierReturn found = lockReturn(returnId);
        ensureStatus(found, SupplierReturnStatus.PENDING_APPROVAL, "decided");
        if (found.getRequestedBy().equals(approver.getId())) {
            throw ApiErrors.forbidden(ErrorCodes.SUPPLIER_RETURN_SELF_DECISION, "You raised this return, so another Super Admin decides it.");
        }
        boolean approved = request.getDecision() == SupplierReturnDTOs.Decision.APPROVED;
        String reason = OptionalText.blankToNull(request.getReason());
        if (!approved && reason == null) {
            throw ApiErrors.fieldError("reason", "A reason is required to reject a return.");
        }
        Long supplierUser = approved ? requireSupplierLogin(order) : null;
        found.setStatus(approved ? SupplierReturnStatus.AWAITING_SUPPLIER : SupplierReturnStatus.REJECTED);
        found.setDecidedBy(approver.getId());
        found.setDecidedAt(clock.instant());
        found.setDecisionReason(reason);
        SupplierReturn saved = returnRepository.save(found);
        event(saved, approved ? SupplierReturnEventType.APPROVED : SupplierReturnEventType.REJECTED, approver.getId(), reason);
        notificationService.createForUser(saved.getRequestedBy(), approved ? "Return approved" : "Return rejected",
                describe(saved, order) + (approved ? " was approved and sent to the supplier to accept." : " was rejected: " + reason),
                NotificationLinks.ORDERS);
        if (approved) {
            notifySupplier(supplierUser, order, "Return of failed pieces for order " + order.getOrderNumber(),
                    describe(saved, order) + ": " + saved.getReason() + ". Open the order to accept the return.");
        }
        return toDto(saved, order);
    }

    /** The supplier declined: a Supplier Manager sends it again, saying what changed or what was agreed. */
    @Transactional
    public SupplierReturnDTOs.Return resend(Long returnId, SupplierReturnDTOs.NoteRequest request, User actor) {
        Order order = lockOrder(orderIdOfReturn(returnId));
        SupplierReturn found = lockReturn(returnId);
        ensureStatus(found, SupplierReturnStatus.DECLINED, "sent again");
        Long supplierUser = requireSupplierLogin(order);
        found.setStatus(SupplierReturnStatus.AWAITING_SUPPLIER);
        found.setSupplierResponseReason(null);
        SupplierReturn saved = returnRepository.save(found);
        event(saved, SupplierReturnEventType.RESENT, actor.getId(), request.getNote().trim());
        notifySupplier(supplierUser, order, "Return sent again for order " + order.getOrderNumber(),
                describe(saved, order) + " was sent to you again: " + request.getNote().trim());
        return toDto(saved, order);
    }

    /** A Supplier Manager cancels a return before its pieces leave TinQa. */
    @Transactional
    public SupplierReturnDTOs.Return cancel(Long returnId, SupplierReturnDTOs.NoteRequest request, User actor) {
        Order order = lockOrder(orderIdOfReturn(returnId));
        SupplierReturn found = lockReturn(returnId);
        if (!CANCELLABLE.contains(found.getStatus())) {
            throw wrongStatus(found, "cancelled (only before the pieces are dispatched)");
        }
        boolean supplierKnew = found.getStatus() != SupplierReturnStatus.PENDING_APPROVAL;
        found.setStatus(SupplierReturnStatus.CANCELLED);
        found.setCancelReason(request.getNote().trim());
        found.setCancelledAt(clock.instant());
        SupplierReturn saved = returnRepository.save(found);
        event(saved, SupplierReturnEventType.CANCELLED, actor.getId(), saved.getCancelReason());
        if (supplierKnew) {
            supplierLoginService.activeUserIdOf(order.getSupplier().getId()).ifPresent(supplierUser -> notifySupplier(supplierUser, order,
                    "Return cancelled for order " + order.getOrderNumber(), describe(saved, order) + " was cancelled: " + saved.getCancelReason()));
        }
        return toDto(saved, order);
    }

    /**
     * The pieces leave TinQa. TinQa ships them: courier, tracking number and the shipping charge the supplier repays.
     * The supplier collects them: the pickup date and who collected them, no charge.
     */
    @Transactional
    public SupplierReturnDTOs.Return dispatch(Long returnId, SupplierReturnDTOs.DispatchRequest request, User actor) {
        Order order = lockOrder(orderIdOfReturn(returnId));
        SupplierReturn found = lockReturn(returnId);
        ensureStatus(found, SupplierReturnStatus.ACCEPTED, "dispatched (the supplier accepts it first)");
        if (request.getDispatchedOn().isAfter(LocalDate.now(clock))) {
            throw ApiErrors.fieldError("dispatchedOn", "The dispatch date cannot be in the future");
        }
        String courier = OptionalText.blankToNull(request.getCourier());
        String tracking = OptionalText.blankToNull(request.getTrackingNumber());
        BigDecimal charge;
        if (found.getShippingMode() == ReturnShippingMode.TINQA_SHIPS) {
            if (courier == null) {
                throw ApiErrors.fieldError("courier", "Name the courier the pieces are shipped with.");
            }
            if (tracking == null) {
                throw ApiErrors.fieldError("trackingNumber", "Give the tracking (AWB) number.");
            }
            if (request.getShippingCharge() == null) {
                throw ApiErrors.fieldError("shippingCharge", "Give the shipping charge the supplier repays (0 if none).");
            }
            charge = request.getShippingCharge().setScale(2, RoundingMode.HALF_UP);
        } else {
            if (request.getShippingCharge() != null && request.getShippingCharge().signum() > 0) {
                throw ApiErrors.fieldError("shippingCharge", "The supplier collects these pieces, so there is no shipping charge.");
            }
            if (tracking != null) {
                throw ApiErrors.fieldError("trackingNumber", "The supplier collects these pieces: there is no tracking number.");
            }
            charge = null;
        }
        Long supplierUser = requireSupplierLogin(order);
        found.setCourier(courier);
        found.setTrackingNumber(tracking);
        found.setDispatchedOn(request.getDispatchedOn());
        found.setShippingCharge(charge);
        found.setDispatchNote(OptionalText.blankToNull(request.getNote()));
        found.setDispatchedBy(actor.getId());
        found.setDispatchedAt(clock.instant());
        found.setStatus(SupplierReturnStatus.IN_TRANSIT);
        SupplierReturn saved = returnRepository.save(found);
        event(saved, SupplierReturnEventType.DISPATCHED, actor.getId(), dispatchText(saved));
        notifySupplier(supplierUser, order, "Returned pieces on their way for order " + order.getOrderNumber(),
                describe(saved, order) + ": " + dispatchText(saved) + ". Confirm in the order once they arrive.");
        return toDto(saved, order);
    }

    // ------------------------------------------------------------------ supplier steps

    /** The supplier accepts a return, or declines it with a reason. The caller has checked (and locked) the order is theirs. */
    @Transactional
    public SupplierReturnDTOs.Return supplierResponse(Order order, Long returnId, SupplierReturnDTOs.ResponseRequest request, User supplierUser) {
        SupplierReturn found = ownReturn(order, returnId);
        ensureStatus(found, SupplierReturnStatus.AWAITING_SUPPLIER, "answered");
        boolean accepted = Boolean.TRUE.equals(request.getAccepted());
        String reason = OptionalText.blankToNull(request.getReason());
        if (!accepted && reason == null) {
            throw ApiErrors.fieldError("reason", "Say why you decline the return.");
        }
        found.setStatus(accepted ? SupplierReturnStatus.ACCEPTED : SupplierReturnStatus.DECLINED);
        found.setSupplierRespondedAt(clock.instant());
        found.setSupplierResponseReason(accepted ? null : reason);
        SupplierReturn saved = returnRepository.save(found);
        event(saved, accepted ? SupplierReturnEventType.ACCEPTED : SupplierReturnEventType.DECLINED, supplierUser.getId(), reason);
        notificationService.createForUser(saved.getRequestedBy(), accepted ? "Supplier accepted a return" : "Supplier declined a return",
                describe(saved, order) + (accepted ? ": " + order.getSupplier().getName() + " accepted it. "
                        + (saved.getShippingMode() == ReturnShippingMode.TINQA_SHIPS ? "Ship the pieces and record the dispatch." : "Record the pickup when they collect the pieces.")
                        : ": " + order.getSupplier().getName() + " declined it: " + reason + ". Send it again or cancel it."), NotificationLinks.ORDERS);
        return toDto(saved, order, false);
    }

    /**
     * The supplier confirms the pieces arrived: they now owe what TinQa paid for them and the return shipping, which is
     * asked of them (or, when nothing is owed, the return is settled).
     */
    @Transactional
    public SupplierReturnDTOs.Return supplierArrived(Order order, Long returnId, SupplierReturnDTOs.ArrivedRequest request, User supplierUser) {
        SupplierReturn found = ownReturn(order, returnId);
        ensureStatus(found, SupplierReturnStatus.IN_TRANSIT, "confirmed as arrived");
        found.setStatus(SupplierReturnStatus.RECEIVED_BY_SUPPLIER);
        found.setReceivedAt(clock.instant());
        returnRepository.saveAndFlush(found);
        event(found, SupplierReturnEventType.RECEIVED, supplierUser.getId(), OptionalText.blankToNull(request == null ? null : request.getNote()));

        SupplierPayment ask = paymentService.askReturnRepayment(order, found.getId(), found.getPiecesValue(), found.getShippingCharge(),
                "Return of " + plain(found.getUnits()) + " " + Objects.toString(order.getUnitType(), "piece(s)") + ": " + found.getReason(),
                found.getRequestedBy());
        if (ask == null) {
            found.setStatus(SupplierReturnStatus.SETTLED);
            found.setSettledAt(clock.instant());
            event(found, SupplierReturnEventType.SETTLED, null, "Nothing to pay back: TinQa had not paid for these pieces and there was no shipping charge.");
        } else {
            found.setRepaymentId(ask.getId());
        }
        SupplierReturn saved = returnRepository.save(found);
        notificationService.createForUser(saved.getRequestedBy(), "Returned pieces reached the supplier",
                describe(saved, order) + " reached " + order.getSupplier().getName() + (ask == null ? "; nothing is owed back."
                        : "; they are asked to pay back " + rupees(ask.totalDue()) + "."), NotificationLinks.ORDERS);
        return toDto(saved, order, false);
    }

    // ------------------------------------------------------------------ rules and mapping

    private SupplierReturnDTOs.OrderReturns orderReturns(Order order, List<SupplierReturn> returns, boolean staff) {
        Stock stock = testedStock(order);
        BigDecimal failed = failedUnits(stock);
        BigDecimal held = stock == null ? BigDecimal.ZERO : returnRepository.unitsHeld(order.getId());
        BigDecimal left = failed.subtract(held).max(BigDecimal.ZERO);
        boolean canRaise = staff && left.signum() > 0 && !CLOSED.contains(order.getOrderStatus())
                && supplierLoginService.activeUserIdOf(order.getSupplier().getId()).isPresent();
        return SupplierReturnDTOs.OrderReturns.builder()
                .orderId(order.getId())
                .tested(stock != null)
                .failedUnits(failed)
                .unitsInReturns(held)
                .unitsLeft(left)
                .canRaise(canRaise)
                .returns(toDtos(returns, Map.of(order.getId(), order), staff))
                .build();
    }

    private Stock testedStock(Order order) {
        return stockRepository.findFirstByOrderOrderNumber(order.getOrderNumber())
                .filter(stock -> stock.getApprovalStatus() == ApprovalStatus.APPROVED && stock.getApprovedUnitsPassed() != null)
                .orElse(null);
    }

    private static BigDecimal failedUnits(Stock stock) {
        return stock == null ? BigDecimal.ZERO : Objects.requireNonNullElse(stock.getApprovedDefectedUnits(), BigDecimal.ZERO);
    }

    private SupplierReturn ownReturn(Order order, Long returnId) {
        SupplierReturn found = lockReturn(returnId);
        if (!found.getOrderId().equals(order.getId()) || !found.getStatus().shownToSupplier()) {
            throw returnNotFound(returnId);
        }
        return found;
    }

    private Long requireSupplierLogin(Order order) {
        return supplierLoginService.activeUserIdOf(order.getSupplier().getId()).orElseThrow(() -> notAllowed(
                "The supplier answers returns in their supplier login, and " + order.getSupplier().getName() + " has no active one."));
    }

    private void notifySupplier(Long supplierUser, Order order, String title, String message) {
        notificationService.createForUser(supplierUser, title, message, NotificationLinks.SUPPLIER_ORDERS);
    }

    private void event(SupplierReturn found, SupplierReturnEventType type, Long actorId, String note) {
        eventRepository.save(SupplierReturnEvent.builder()
                .returnId(found.getId())
                .orderId(found.getOrderId())
                .type(type)
                .actorId(actorId)
                .at(clock.instant())
                .note(note)
                .build());
    }

    private static String dispatchText(SupplierReturn found) {
        if (found.getShippingMode() == ReturnShippingMode.SUPPLIER_PICKUP) {
            return "collected by the supplier on " + found.getDispatchedOn() + (found.getCourier() != null ? " (" + found.getCourier() + ")" : "");
        }
        return "shipped on " + found.getDispatchedOn() + " with " + found.getCourier() + ", tracking " + found.getTrackingNumber()
                + ", shipping charge " + rupees(Objects.requireNonNullElse(found.getShippingCharge(), BigDecimal.ZERO));
    }

    private static String describe(SupplierReturn found, Order order) {
        return "Return of " + plain(found.getUnits()) + " " + Objects.toString(order.getUnitType(), "piece(s)") + " of order "
                + order.getOrderNumber() + " (" + order.getSupplier().getName() + ", worth " + rupees(found.getPiecesValue()) + ")";
    }

    private SupplierReturnDTOs.Return toDto(SupplierReturn found, Order order) {
        return toDto(found, order, true);
    }

    private SupplierReturnDTOs.Return toDto(SupplierReturn found, Order order, boolean staff) {
        return toDtos(List.of(found), Map.of(order.getId(), order), staff).getFirst();
    }

    private List<SupplierReturnDTOs.Return> toDtos(List<SupplierReturn> returns, Map<Long, Order> orders, boolean staff) {
        if (returns.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SupplierReturnEvent>> events = eventRepository.findByReturnIdInOrderByAtAscIdAsc(returns.stream().map(SupplierReturn::getId).toList())
                .stream().collect(Collectors.groupingBy(SupplierReturnEvent::getReturnId));
        Map<Long, ActorDirectory.Actor> actors = actorDirectory.of(events.values().stream().flatMap(List::stream)
                .map(SupplierReturnEvent::getActorId).filter(Objects::nonNull).distinct().toList());
        Map<Long, User> users = staff ? userRepository.findAllById(returns.stream().flatMap(r -> Stream.of(r.getRequestedBy(), r.getDecidedBy()))
                .filter(Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(User::getId, Function.identity())) : Map.of();
        Map<Long, SupplierPayment> repayments = paymentRepository.findAllById(returns.stream().map(SupplierReturn::getRepaymentId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(SupplierPayment::getId, Function.identity()));
        return returns.stream().map(found -> {
            Order order = orders.get(found.getOrderId());
            SupplierPayment repayment = found.getRepaymentId() != null ? repayments.get(found.getRepaymentId()) : null;
            return SupplierReturnDTOs.Return.builder()
                    .id(found.getId())
                    .orderId(found.getOrderId())
                    .orderNumber(order != null ? order.getOrderNumber() : null)
                    .supplierName(order != null ? order.getSupplier().getName() : null)
                    .itemName(order != null && order.getItem() != null ? order.getItem().getName() : null)
                    .units(found.getUnits())
                    .unitType(order != null ? order.getUnitType() : null)
                    .unitPrice(order != null ? order.getUnitPrice() : null)
                    .reason(found.getReason())
                    .pieceDetails(found.getPieceDetails())
                    .shippingMode(found.getShippingMode())
                    .status(found.getStatus())
                    .piecesValue(found.getPiecesValue())
                    .courier(found.getCourier())
                    .trackingNumber(found.getTrackingNumber())
                    .dispatchedOn(found.getDispatchedOn())
                    .shippingCharge(found.getShippingCharge())
                    .repaymentId(found.getRepaymentId())
                    .amountDue(repayment != null ? repayment.totalDue() : null)
                    .requestedByUsername(staff ? usernameOf(users, found.getRequestedBy()) : null)
                    .requestedAt(found.getRequestedAt())
                    .decidedByUsername(staff ? usernameOf(users, found.getDecidedBy()) : null)
                    .decidedAt(found.getDecidedAt())
                    .decisionReason(staff ? found.getDecisionReason() : null)
                    .supplierRespondedAt(found.getSupplierRespondedAt())
                    .supplierResponseReason(found.getSupplierResponseReason())
                    .receivedAt(found.getReceivedAt())
                    .settledAt(found.getSettledAt())
                    .cancelReason(found.getCancelReason())
                    .events(events.getOrDefault(found.getId(), List.of()).stream()
                            .filter(event -> staff || event.getType().shownToSupplier())
                            .map(event -> {
                                ActorDirectory.Actor actor = event.getActorId() == null ? null : actors.get(event.getActorId());
                                return SupplierReturnDTOs.Event.builder()
                                        .id(event.getId())
                                        .type(event.getType())
                                        .at(event.getAt())
                                        .actorRole(actor != null ? actor.role() : null)
                                        .actorName(staff && actor != null && !actor.supplier() ? actor.name() : null)
                                        .note(event.getNote())
                                        .build();
                            }).toList())
                    .build();
        }).toList();
    }

    private static String usernameOf(Map<Long, User> users, Long userId) {
        User user = userId == null ? null : users.get(userId);
        return user != null ? user.getUsername() : null;
    }

    private static void ensureStatus(SupplierReturn found, SupplierReturnStatus expected, String action) {
        if (found.getStatus() != expected) {
            throw wrongStatus(found, action);
        }
    }

    private static RuntimeException wrongStatus(SupplierReturn found, String action) {
        return ApiErrors.conflict(ErrorCodes.SUPPLIER_RETURN_WRONG_STATUS, "This return is "
                + found.getStatus().name().toLowerCase().replace('_', ' ') + ", so it can't be " + action + ".");
    }

    private static RuntimeException notAllowed(String message) {
        return ApiErrors.conflict(ErrorCodes.SUPPLIER_RETURN_NOT_ALLOWED, message);
    }

    // Order first, then the return: the same order every writer of an order's money takes its locks in
    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> orderNotFound(orderId));
    }

    private SupplierReturn lockReturn(Long returnId) {
        return returnRepository.findByIdForUpdate(returnId).orElseThrow(() -> returnNotFound(returnId));
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String rupees(BigDecimal amount) {
        return "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static ResourceNotFoundException orderNotFound(Long orderId) {
        return new ResourceNotFoundException("Order not found with id: " + orderId);
    }

    private static ResourceNotFoundException returnNotFound(Long returnId) {
        return new ResourceNotFoundException("Return not found with id: " + returnId);
    }
}
