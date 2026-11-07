package com.tinqa.procurement.order.timeline;

import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.employee.service.ActorDirectory;
import com.tinqa.procurement.order.change.OrderChangeRequest;
import com.tinqa.procurement.order.change.OrderChangeRequestRepository;
import com.tinqa.procurement.order.change.OrderChangeStatus;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.entity.OrderStatusHistory;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.invoice.InvoiceVerificationStatus;
import com.tinqa.procurement.order.invoice.OrderInvoice;
import com.tinqa.procurement.order.invoice.OrderInvoiceRepository;
import com.tinqa.procurement.order.payment.PaymentMethod;
import com.tinqa.procurement.order.payment.SupplierPayment;
import com.tinqa.procurement.order.payment.SupplierPaymentEvent;
import com.tinqa.procurement.order.payment.SupplierPaymentEventRepository;
import com.tinqa.procurement.order.payment.SupplierPaymentEventType;
import com.tinqa.procurement.order.payment.SupplierPaymentRepository;
import com.tinqa.procurement.order.payment.SupplierRefundClaim;
import com.tinqa.procurement.order.payment.SupplierRefundClaimRepository;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.order.repository.OrderStatusHistoryRepository;
import com.tinqa.procurement.order.supplierreturn.ReturnShippingMode;
import com.tinqa.procurement.order.supplierreturn.SupplierReturn;
import com.tinqa.procurement.order.supplierreturn.SupplierReturnEvent;
import com.tinqa.procurement.order.supplierreturn.SupplierReturnEventRepository;
import com.tinqa.procurement.order.supplierreturn.SupplierReturnRepository;
import com.tinqa.procurement.order.timeline.OrderTimelineDTOs.Event;
import com.tinqa.procurement.order.timeline.OrderTimelineDTOs.Kind;
import com.tinqa.procurement.order.timeline.OrderTimelineDTOs.Tone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An order's whole flow as one history: the quotation and order status changes (who submitted, approved, accepted,
 * shipped...), the supplier's change requests, every step of every payment and repayment, the returns of failed pieces,
 * the invoices and the refund claim. Built
 * in a fixed number of queries (one per source, plus two for the people), never one per event.
 *
 * <p>Staff see every step with the person's name and role. The supplier sees roles only ("Super Admin"), and of the
 * payments only the steps from the payment being made on, never TinQa's internal requests and approvals.
 */
@Service
@RequiredArgsConstructor
public class OrderTimelineService {

    private static final String SYSTEM = null;

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OrderChangeRequestRepository changeRequestRepository;
    private final SupplierPaymentRepository paymentRepository;
    private final SupplierPaymentEventRepository paymentEventRepository;
    private final OrderInvoiceRepository invoiceRepository;
    private final SupplierRefundClaimRepository claimRepository;
    private final SupplierReturnRepository returnRepository;
    private final SupplierReturnEventRepository returnEventRepository;
    private final ActorDirectory actorDirectory;

    /** The staff view of an order's flow. */
    @Transactional(readOnly = true)
    public OrderTimelineDTOs.Timeline forStaff(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
        return build(order, true);
    }

    /** The supplier's view of one of their own orders (the caller has checked it is theirs). */
    @Transactional(readOnly = true)
    public OrderTimelineDTOs.Timeline forSupplier(Order order) {
        return build(order, false);
    }

    private OrderTimelineDTOs.Timeline build(Order order, boolean staff) {
        Long orderId = order.getId();
        List<OrderStatusHistory> history = statusHistoryRepository.findByOrderIdOrderByChangedAtAscIdAsc(orderId);
        List<OrderChangeRequest> changes = changeRequestRepository.findByOrderIdOrderByRequestedAtDesc(orderId);
        List<SupplierPaymentEvent> paymentEvents = paymentEventRepository.findByOrderIdOrderByAtAscIdAsc(orderId).stream()
                .filter(event -> staff || event.getType().shownToSupplier()).toList();
        Map<Long, SupplierPayment> payments = paymentEvents.isEmpty() ? Map.of()
                : paymentRepository.findByOrderIdOrderByRecordedAtAscIdAsc(orderId).stream()
                .collect(Collectors.toMap(SupplierPayment::getId, Function.identity()));
        List<OrderInvoice> invoices = invoiceRepository.findByOrderIdOrderByIdAsc(orderId);
        SupplierRefundClaim claim = claimRepository.findByOrderId(orderId).orElse(null);
        List<SupplierReturnEvent> returnEvents = returnEventRepository.findByOrderIdOrderByAtAscIdAsc(orderId).stream()
                .filter(event -> staff || event.getType().shownToSupplier()).toList();
        Map<Long, SupplierReturn> returns = returnEvents.isEmpty() ? Map.of()
                : returnRepository.findByOrderIdOrderByRequestedAtDescIdDesc(orderId).stream()
                .collect(Collectors.toMap(SupplierReturn::getId, Function.identity()));

        Map<Long, ActorDirectory.Actor> actors = actorDirectory.of(Stream.of(
                history.stream().map(OrderStatusHistory::getChangedBy),
                changes.stream().flatMap(change -> Stream.of(change.getRequestedBy(), change.getDecidedBy())),
                paymentEvents.stream().map(SupplierPaymentEvent::getActorId),
                returnEvents.stream().map(SupplierReturnEvent::getActorId),
                invoices.stream().flatMap(invoice -> Stream.of(invoice.getUploadedBy(), invoice.getOverrideBy())),
                Stream.of(claim != null ? claim.getAdjustedBy() : null)
        ).flatMap(Function.identity()).filter(Objects::nonNull).toList());
        Builder events = new Builder(actors, staff);

        for (OrderStatusHistory step : history) {
            events.status(step);
        }
        for (OrderChangeRequest change : changes) {
            events.change(change);
        }
        for (SupplierPaymentEvent event : paymentEvents) {
            SupplierPayment payment = payments.get(event.getPaymentId());
            if (payment != null) {
                events.payment(payment, event);
            }
        }
        for (SupplierReturnEvent event : returnEvents) {
            SupplierReturn found = returns.get(event.getReturnId());
            // The supplier sees only returns a Super Admin sent them (not one cancelled or rejected before that)
            boolean shown = found != null && (staff || (found.getDecidedAt() != null
                    && found.getStatus() != com.tinqa.procurement.order.supplierreturn.SupplierReturnStatus.REJECTED));
            if (shown) {
                events.supplierReturn(found, event, order.getUnitType());
            }
        }
        for (OrderInvoice invoice : invoices) {
            events.invoice(invoice);
        }
        if (claim != null) {
            events.claim(claim);
        }
        return OrderTimelineDTOs.Timeline.builder()
                .orderId(orderId)
                .orderNumber(order.getOrderNumber())
                .events(events.sorted())
                .build();
    }

    /** Collects the events of one timeline, and words them. */
    private static final class Builder {

        private record Ordered(Event event, int sequence) {
        }

        private final Map<Long, ActorDirectory.Actor> actors;
        private final boolean staff;
        private final List<Ordered> events = new ArrayList<>();

        Builder(Map<Long, ActorDirectory.Actor> actors, boolean staff) {
            this.actors = actors;
            this.staff = staff;
        }

        List<Event> sorted() {
            return events.stream()
                    .sorted(Comparator.comparing((Ordered ordered) -> ordered.event().getAt()).thenComparingInt(Ordered::sequence))
                    .map(Ordered::event).toList();
        }

        private void add(String id, Instant at, Kind kind, Long actorId, String title, String detail, Tone tone,
                         String branchId, String branchTitle) {
            if (at == null) {
                return;
            }
            ActorDirectory.Actor actor = actorId == null ? null : actors.get(actorId);
            events.add(new Ordered(Event.builder()
                    .id(id)
                    .at(at)
                    .kind(kind)
                    .title(who(actor) + " " + title)
                    .detail(detail)
                    .actorRole(actor != null ? actor.role() : SYSTEM)
                    .actorName(staff && actor != null && !actor.supplier() ? actor.name() : null)
                    .tone(tone)
                    .branchId(branchId)
                    .branchTitle(branchTitle)
                    .build(), events.size()));
        }

        private static String who(ActorDirectory.Actor actor) {
            if (actor == null) {
                return "TinQa";
            }
            return actor.supplier() ? "The supplier" : actor.role();
        }

        private boolean bySupplier(Long actorId) {
            ActorDirectory.Actor actor = actorId == null ? null : actors.get(actorId);
            return actor != null && actor.supplier();
        }

        void status(OrderStatusHistory step) {
            OrderStatus from = step.getFromStatus();
            OrderStatus to = step.getToStatus();
            boolean supplier = bySupplier(step.getChangedBy());
            String title;
            if (from == null) {
                title = supplier ? "submitted the quotation" : "created the quotation";
            } else if (from == OrderStatus.QUOTATION && to == OrderStatus.PENDING) {
                title = "approved the quotation and sent it for final approval";
            } else if (from == OrderStatus.PENDING && to == OrderStatus.SUPPLIER_LEVEL_PENDING) {
                title = "gave the final approval; the order went to the supplier to accept";
            } else if (from == OrderStatus.SUPPLIER_LEVEL_PENDING && to == OrderStatus.CONFIRMED) {
                title = supplier ? "accepted the order" : "confirmed the order for the supplier";
            } else if (to == OrderStatus.CANCELLED) {
                title = switch (from) {
                    case QUOTATION -> "rejected the quotation";
                    case PENDING -> "rejected the order";
                    case SUPPLIER_LEVEL_PENDING -> supplier ? "declined the order" : "cancelled the order";
                    default -> "cancelled the order";
                };
            } else {
                title = switch (to) {
                    case SHIPPED -> "marked the order shipped";
                    case DELIVERED -> "marked the order delivered";
                    case RETURN_REQUESTED -> "asked to return the order";
                    case RETURN_APPROVED -> "approved the return";
                    case RETURN_REJECTED -> "rejected the return";
                    case RETURN_PICKED_UP -> "marked the return picked up";
                    case RETURN_RECEIVED -> "marked the return received";
                    case REFUND_INITIATED -> "started the refund";
                    case REFUND_FAILED -> "marked the refund failed";
                    case REFUND_COMPLETED -> "marked the refund completed";
                    default -> "moved the order to " + to.label().toLowerCase();
                };
            }
            List<String> details = new ArrayList<>();
            if (step.getReason() != null) details.add(step.getReason());
            if (step.getActualDelivery() != null) details.add("Delivered on " + step.getActualDelivery());
            Tone tone = to == OrderStatus.CANCELLED || to == OrderStatus.RETURN_REJECTED || to == OrderStatus.REFUND_FAILED
                    ? Tone.REJECTED : Tone.DONE;
            add("status-" + step.getId(), step.getChangedAt(), Kind.STATUS, step.getChangedBy(), title,
                    details.isEmpty() ? null : String.join(" · ", details), tone, null, null);
        }

        void change(OrderChangeRequest change) {
            String branch = "change-" + change.getId();
            String branchTitle = "Quotation change #" + change.getId();
            add(branch + "-requested", change.getRequestedAt(), Kind.CHANGE_REQUEST, change.getRequestedBy(),
                    "asked to change the quotation", change.getNote(), Tone.WAITING, branch, branchTitle);
            OrderChangeStatus status = change.getStatus();
            if (status == OrderChangeStatus.PENDING) {
                return;
            }
            String title = switch (status) {
                case APPROVED -> "approved the change";
                case REJECTED -> "rejected the change";
                case WITHDRAWN -> "withdrew the change";
                case PENDING -> throw new IllegalStateException();
            };
            add(branch + "-decided", change.getDecidedAt(), Kind.CHANGE_REQUEST,
                    status == OrderChangeStatus.WITHDRAWN && change.getDecidedBy() == null ? change.getRequestedBy() : change.getDecidedBy(),
                    title, change.getDecisionReason(), status == OrderChangeStatus.APPROVED ? Tone.DONE
                            : status == OrderChangeStatus.REJECTED ? Tone.REJECTED : Tone.INFO, branch, branchTitle);
        }

        void payment(SupplierPayment payment, SupplierPaymentEvent event) {
            String branch = "payment-" + payment.getId();
            String what = switch (payment.getKind()) {
                case PAYMENT -> "payment";
                case REFUND -> "refund";
                case REVERSAL -> "reversal";
            };
            String title = switch (event.getType()) {
                case REQUESTED -> "requested the " + milestone(payment) + " payment";
                case CORRECTED -> "corrected the " + what;
                case REQUEST_APPROVED -> "approved the payment request";
                case REQUEST_REJECTED -> "rejected the payment request";
                case PAID -> "paid the supplier";
                case NOT_RECEIVED -> "said the payment did not arrive";
                case RESENT -> "checked the payment and sent it again";
                case RECEIVED -> "confirmed the payment was received";
                case WITHDRAWN -> "withdrew the " + what;
                case RECORDED -> "recorded the " + what;
                case APPROVED -> "confirmed the " + what;
                case REJECTED -> "rejected the " + what;
                case REPAYMENT_REQUESTED -> "asked the supplier to pay it back";
                case REPAYMENT_SENT -> "paid it back";
                case REPAYMENT_RESENT -> "checked it and sent it again";
                case REPAYMENT_MISSING -> "said the money did not arrive";
                case REPAYMENT_RECEIVED -> "confirmed the money arrived";
            };
            Tone tone = switch (event.getType()) {
                case REQUESTED, CORRECTED, PAID, RESENT, RECORDED, REPAYMENT_REQUESTED, REPAYMENT_SENT, REPAYMENT_RESENT -> Tone.WAITING;
                case REQUEST_APPROVED, RECEIVED, APPROVED, REPAYMENT_RECEIVED -> Tone.DONE;
                case REQUEST_REJECTED, NOT_RECEIVED, REJECTED, REPAYMENT_MISSING -> Tone.REJECTED;
                case WITHDRAWN -> Tone.INFO;
            };
            List<String> details = new ArrayList<>();
            if (event.getReference() != null) details.add(referenceLabel(payment.getMethod()) + ": " + event.getReference());
            if (event.getPaidOn() != null) details.add("paid on " + event.getPaidOn());
            boolean noteShown = staff || event.getType() == SupplierPaymentEventType.PAID || event.getType() == SupplierPaymentEventType.RESENT
                    || event.getType() == SupplierPaymentEventType.NOT_RECEIVED || event.getType().repayment();
            if (event.getNote() != null && noteShown) details.add(event.getNote());
            if (event.getProofDocumentId() != null) details.add(event.getType().repayment() ? "receipt attached" : "proof attached");
            add(branch + "-" + event.getId(), event.getAt(), Kind.PAYMENT, event.getActorId(), title,
                    details.isEmpty() ? null : String.join(" · ", details), tone, branch, branchTitle(payment));
        }

        void supplierReturn(SupplierReturn found, SupplierReturnEvent event, String unitType) {
            String branch = "return-" + found.getId();
            String branchTitle = "Return of " + found.getUnits().stripTrailingZeros().toPlainString() + " "
                    + Objects.toString(unitType, "piece(s)") + " (failed testing)";
            String title = switch (event.getType()) {
                case REQUESTED -> "raised a return of the failed pieces";
                case APPROVED -> "approved the return; it went to the supplier";
                case REJECTED -> "rejected the return";
                case ACCEPTED -> "accepted the return";
                case DECLINED -> "declined the return";
                case RESENT -> "sent the return to the supplier again";
                case DISPATCHED -> found.getShippingMode() == ReturnShippingMode.SUPPLIER_PICKUP
                        ? "handed the pieces to the supplier" : "shipped the pieces to the supplier";
                case RECEIVED -> "confirmed the pieces arrived";
                case SETTLED -> "settled the return";
                case CANCELLED -> "cancelled the return";
            };
            Tone tone = switch (event.getType()) {
                case REQUESTED, RESENT, DISPATCHED -> Tone.WAITING;
                case APPROVED, ACCEPTED, RECEIVED, SETTLED -> Tone.DONE;
                case REJECTED, DECLINED -> Tone.REJECTED;
                case CANCELLED -> Tone.INFO;
            };
            add(branch + "-" + event.getId(), event.getAt(), Kind.RETURN, event.getActorId(), title, event.getNote(), tone, branch, branchTitle);
        }

        void invoice(OrderInvoice invoice) {
            String status = switch (invoice.getStatus()) {
                case MATCHED -> "it matches the quotation";
                case MISMATCH -> "it does not match the quotation";
                case UNVERIFIED -> "it could not be checked automatically";
            };
            add("invoice-" + invoice.getId(), invoice.getUploadedAt(), Kind.INVOICE, invoice.getUploadedBy(),
                    "uploaded the supplier's invoice", (invoice.getInvoiceNumber() != null ? "Invoice " + invoice.getInvoiceNumber() + ": " : "") + status,
                    invoice.getStatus() == InvoiceVerificationStatus.MATCHED ? Tone.DONE : Tone.WAITING, null, null);
            if (invoice.getOverrideAt() != null) {
                add("invoice-" + invoice.getId() + "-override", invoice.getOverrideAt(), Kind.INVOICE, invoice.getOverrideBy(),
                        "accepted the invoice although it did not match", invoice.getOverrideReason(), Tone.DONE, null, null);
            }
        }

        void claim(SupplierRefundClaim claim) {
            String id = "claim-" + claim.getId();
            add(id + "-opened", claim.getOpenedAt(), Kind.REFUND_CLAIM, null,
                    "opened a refund claim against the supplier", rupees(claim.getClaimedAmount()) + " was overpaid", Tone.WAITING, null, null);
            if (claim.getAdjustedAt() != null) {
                add(id + "-adjusted", claim.getAdjustedAt(), Kind.REFUND_CLAIM, claim.getAdjustedBy(),
                        "set the refund due to " + rupees(claim.getAgreedAmount()), claim.getAdjustReason(), Tone.INFO, null, null);
            }
            if (claim.getSettledAt() != null) {
                add(id + "-settled", claim.getSettledAt(), Kind.REFUND_CLAIM, null,
                        "marked the refund claim settled", null, Tone.DONE, null, null);
            }
        }

        private static String milestone(SupplierPayment payment) {
            return payment.getMilestone() == null ? "" : switch (payment.getMilestone()) {
                case ADVANCE -> "advance";
                case MIDTERM -> "midterm";
                case FINAL -> "final";
            };
        }

        private static String branchTitle(SupplierPayment payment) {
            String method = payment.getMethod().name().replace('_', ' ').toLowerCase();
            return switch (payment.getKind()) {
                case PAYMENT -> capitalised(milestone(payment)) + " payment " + rupees(payment.getAmount()) + " (" + method + ")";
                case REFUND -> payment.getReturnId() != null
                        ? "Repayment " + rupees(payment.totalDue()) + " for returned pieces (" + method + ")"
                        : "Refund " + rupees(payment.getAmount()) + " from the supplier (" + method + ")";
                case REVERSAL -> "Reversal of " + rupees(payment.getAmount()) + (payment.getMilestone() != null ? " (paid back by the supplier)" : "");
            };
        }

        private static String referenceLabel(PaymentMethod method) {
            return switch (method) {
                case ONLINE -> "Transaction ID";
                case BANK_TRANSFER -> "UTR number";
                case CHEQUE -> "Cheque number";
                case CASH -> "Receipt number";
            };
        }

        private static String capitalised(String text) {
            return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }

        private static String rupees(BigDecimal amount) {
            return "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
    }
}
