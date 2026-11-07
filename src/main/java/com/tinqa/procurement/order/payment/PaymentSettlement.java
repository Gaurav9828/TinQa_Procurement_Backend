package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.finance.service.PurchasePostingRecorder;
import com.tinqa.procurement.order.entity.Order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Why an order is worth less after testing: the quoted value, the units that failed or never arrived, the shipment
 * (dropped when no unit passed) and the GST on what was taken off, down to the worth after testing that the final
 * payment is worked out on. The lines add up exactly: {@code orderValue - failedValue - shortValue - shipmentDropped
 * - gstReduction = payable}, where payable is the figure SupplierPaymentService uses (PurchasePostingRecorder's value
 * plus its GST). Read-only: it explains the figures, it decides nothing.
 */
public final class PaymentSettlement {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private PaymentSettlement() {
    }

    /**
     * The breakdown for a tested order: {@code orderValue} is the quoted total with GST, {@code payable} the worth
     * after testing, {@code passed} and {@code failed} the L2-approved units of its stock entry.
     */
    public static SupplierPaymentDTOs.Settlement of(Order order, BigDecimal orderValue, BigDecimal payable, BigDecimal passed, BigDecimal failed) {
        BigDecimal unitPrice = order.getUnitPrice();
        BigDecimal ordered = order.getOrderQuantity();
        BigDecimal shipment = money(order.getShipmentPrice());
        // Delivered short (or more than ordered, as a negative): what is neither passed nor failed
        BigDecimal shortUnits = ordered.subtract(passed).subtract(failed);

        BigDecimal quotedGoods = money(unitPrice.multiply(ordered));
        BigDecimal passedGoods = passed.signum() > 0 ? money(unitPrice.multiply(passed)) : ZERO;
        BigDecimal failedValue = money(unitPrice.multiply(failed));
        // The rest of the goods' difference, so a rounding paisa never leaves the lines unbalanced
        BigDecimal shortValue = quotedGoods.subtract(passedGoods).subtract(failedValue);
        // The shipment is paid once any unit passed (PurchasePostingRecorder.purchaseValue)
        BigDecimal shipmentDropped = passed.signum() > 0 ? ZERO : shipment;

        BigDecimal quotedTaxable = quotedGoods.add(shipment);
        BigDecimal passedTaxable = passedGoods.add(shipment.subtract(shipmentDropped));
        // The quotation's own GST total (its grand total less the taxable value), and the GST on what passed
        BigDecimal quotedGst = orderValue.subtract(quotedTaxable);
        BigDecimal passedGst = payable.subtract(passedTaxable);

        return SupplierPaymentDTOs.Settlement.builder()
                .unitType(order.getUnitType())
                .unitPrice(unitPrice)
                .orderedUnits(ordered)
                .passedUnits(passed)
                .failedUnits(failed)
                .shortUnits(shortUnits)
                .shipment(shipment)
                .gstRate(PurchasePostingRecorder.gstOn(order, passedTaxable.signum() > 0 ? passedTaxable : quotedTaxable).rate())
                .quotedGoods(quotedGoods)
                .quotedTaxable(quotedTaxable)
                .quotedGst(quotedGst)
                .orderValue(orderValue)
                .failedValue(failedValue)
                .shortValue(shortValue)
                .shipmentDropped(shipmentDropped)
                .gstReduction(quotedGst.subtract(passedGst))
                .reduction(orderValue.subtract(payable))
                .passedGoods(passedGoods)
                .passedTaxable(passedTaxable)
                .passedGst(passedGst)
                .payable(payable)
                .build();
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
