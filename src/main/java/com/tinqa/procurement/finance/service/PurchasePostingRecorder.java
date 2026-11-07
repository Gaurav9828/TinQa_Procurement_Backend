package com.tinqa.procurement.finance.service;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.finance.entity.PurchasePosting;
import com.tinqa.procurement.finance.enums.PurchasePostingReason;
import com.tinqa.procurement.finance.repository.PurchasePostingRepository;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.tax.QuotationTax;
import com.tinqa.procurement.stock.entity.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * Books a supplier purchase from stock Admin L2 has approved, not from delivery or from what was only recorded: the units
 * L2 approved as passed are bought, the rest go back to the supplier. Each approval (of an entry, or of a change to one)
 * re-values the order and records the difference to what is already booked, with the GST on it, in the caller's
 * transaction, so the approval and its money move commit (or fail) together.
 *
 * <p>GST comes from the quotation's breakup (prices there are before GST): CGST + SGST or IGST at the quoted rate on the
 * booked value, the quoted amounts themselves when the whole quoted value is booked, cess in proportion. No breakup, no
 * GST (the supplier's invoice carried none, so there is no input tax to claim).
 *
 * <p>Callers must hold the order's row lock, so two changes to one order cannot book the same difference twice.
 */
@Service
@RequiredArgsConstructor
public class PurchasePostingRecorder {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO_HUNDRED = new BigDecimal("200");

    private final PurchasePostingRepository postingRepository;
    private final Clock clock;

    /** The GST on a booked value. Amounts are paise-rounded rupees; rate and supply type are null without a breakup. */
    public record Gst(BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal cess, BigDecimal rate, String supplyType,
                      String hsnSac) {

        static final Gst NONE = new Gst(ZERO, ZERO, ZERO, ZERO, null, null, null);

        public BigDecimal total() {
            return cgst.add(sgst).add(igst).add(cess);
        }
    }

    /** What the order is worth for {@code stock}: unit price x L2-approved passed units, plus shipment once any passed. */
    public static BigDecimal purchaseValue(Order order, Stock stock) {
        BigDecimal approved = approvedUnits(stock);
        if (approved.signum() <= 0) {
            return ZERO;
        }
        BigDecimal shipment = order.getShipmentPrice() == null ? BigDecimal.ZERO : order.getShipmentPrice();
        return order.getUnitPrice().multiply(approved).setScale(2, RoundingMode.HALF_UP).add(shipment);
    }

    /** The GST on {@code taxable} under the order's quotation (same rule as the one-time true-up migration of 2026). */
    public static Gst gstOn(Order order, BigDecimal taxable) {
        Map<String, Object> breakup = order.getTaxBreakup();
        if (!QuotationTax.present(breakup)) {
            return Gst.NONE;
        }
        BigDecimal rate = new BigDecimal(breakup.get(QuotationTax.GST_RATE).toString().trim());
        String supplyType = Objects.toString(breakup.get(QuotationTax.SUPPLY_TYPE), null);
        String hsnSac = breakup.get(QuotationTax.HSN_SAC) == null ? null : breakup.get(QuotationTax.HSN_SAC).toString();
        if (taxable.signum() == 0) {
            return new Gst(ZERO, ZERO, ZERO, ZERO, rate, supplyType, hsnSac);
        }
        boolean intraState = QuotationTax.INTRA_STATE.equals(supplyType);
        BigDecimal quotedTaxable = QuotationTax.amountOf(breakup, QuotationTax.TAXABLE_VALUE);
        BigDecimal quotedCess = QuotationTax.amountOf(breakup, QuotationTax.CESS);
        if (quotedTaxable.compareTo(taxable) == 0) {
            // The whole quoted value: the amounts on the quotation (the supplier's own rounding)
            return new Gst(intraState ? QuotationTax.amountOf(breakup, QuotationTax.CGST) : ZERO,
                    intraState ? QuotationTax.amountOf(breakup, QuotationTax.SGST) : ZERO,
                    intraState ? ZERO : QuotationTax.amountOf(breakup, QuotationTax.IGST),
                    quotedCess, rate, supplyType, hsnSac);
        }
        BigDecimal cess = quotedTaxable.signum() == 0 ? ZERO
                : quotedCess.multiply(taxable).divide(quotedTaxable, 2, RoundingMode.HALF_UP);
        if (intraState) {
            BigDecimal half = taxable.multiply(rate).divide(TWO_HUNDRED, 2, RoundingMode.HALF_UP);
            return new Gst(half, half, ZERO, cess, rate, supplyType, hsnSac);
        }
        return new Gst(ZERO, ZERO, taxable.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP), cess, rate, supplyType, hsnSac);
    }

    /** Books the difference between the stock entry's approved value (and its GST) now and what is booked for its order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rebook(Order order, Stock stock, PurchasePostingReason reason) {
        BigDecimal value = purchaseValue(order, stock);
        record(order, stock, reason, approvedUnits(stock), value, gstOn(order, value));
    }

    /** The supplier refunded the order: everything booked for it, GST included, comes back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(Order order) {
        record(order, null, PurchasePostingReason.ORDER_REFUNDED, BigDecimal.ZERO, ZERO, gstOn(order, ZERO));
    }

    private void record(Order order, Stock stock, PurchasePostingReason reason, BigDecimal units, BigDecimal target, Gst gst) {
        PurchasePostingRepository.BookedGst booked = postingRepository.sumGstBookedForOrder(order.getId());
        BigDecimal bookedCgst = money(booked.getCgst());
        BigDecimal bookedSgst = money(booked.getSgst());
        BigDecimal bookedIgst = money(booked.getIgst());
        BigDecimal bookedCess = money(booked.getCess());
        // GST booked under other terms (CGST + SGST where IGST applies now, or the reverse, or a breakup since removed)
        // is taken back first in a row of its own, so every row has one supply type
        boolean intraBooked = bookedCgst.signum() != 0 || bookedSgst.signum() != 0;
        boolean interBooked = bookedIgst.signum() != 0;
        boolean termsChanged = (intraBooked && !QuotationTax.INTRA_STATE.equals(gst.supplyType()))
                || (interBooked && !QuotationTax.INTER_STATE.equals(gst.supplyType()))
                || (gst.supplyType() == null && bookedCess.signum() != 0);
        if (termsChanged) {
            postingRepository.save(posting(order, stock, PurchasePostingReason.GST_TERMS_CHANGED, units, ZERO,
                    new Gst(bookedCgst.negate(), bookedSgst.negate(), bookedIgst.negate(), bookedCess.negate(), null,
                            intraBooked ? QuotationTax.INTRA_STATE : QuotationTax.INTER_STATE, null)));
            bookedCgst = ZERO;
            bookedSgst = ZERO;
            bookedIgst = ZERO;
            bookedCess = ZERO;
        }

        BigDecimal difference = target.subtract(postingRepository.sumBookedForOrder(order.getId()));
        Gst gstDifference = new Gst(gst.cgst().subtract(bookedCgst), gst.sgst().subtract(bookedSgst),
                gst.igst().subtract(bookedIgst), gst.cess().subtract(bookedCess), gst.rate(), gst.supplyType(), gst.hsnSac());
        if (difference.signum() == 0 && gstDifference.cgst().signum() == 0 && gstDifference.sgst().signum() == 0
                && gstDifference.igst().signum() == 0 && gstDifference.cess().signum() == 0) {
            return;
        }
        postingRepository.save(posting(order, stock, reason, units, difference, gstDifference));
    }

    private PurchasePosting posting(Order order, Stock stock, PurchasePostingReason reason, BigDecimal units,
                                    BigDecimal amount, Gst gst) {
        return PurchasePosting.builder()
                .order(order)
                .stock(stock)
                .reason(reason)
                .unitsPassed(units)
                .amount(amount)
                .cgst(gst.cgst())
                .sgst(gst.sgst())
                .igst(gst.igst())
                .cess(gst.cess())
                .gstRate(gst.rate())
                .supplyType(gst.supplyType())
                .hsnSac(gst.hsnSac())
                .occurredOn(LocalDate.now(clock.withZone(BUSINESS_ZONE)))
                .createdAt(clock.instant())
                .build();
    }

    /** The passed units Admin L2 approved: none while the entry awaits approval or after it was rejected. */
    public static BigDecimal approvedUnits(Stock stock) {
        if (stock.getApprovalStatus() != ApprovalStatus.APPROVED || stock.getApprovedUnitsPassed() == null) {
            return BigDecimal.ZERO;
        }
        return stock.getApprovedUnitsPassed();
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
