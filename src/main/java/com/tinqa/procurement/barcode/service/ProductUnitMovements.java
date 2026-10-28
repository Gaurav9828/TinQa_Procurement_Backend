package com.tinqa.procurement.barcode.service;

import com.tinqa.procurement.barcode.entity.Barcode;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import com.tinqa.procurement.barcode.repository.BarcodeRepository;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import com.tinqa.procurement.productapproval.repository.ProductApprovalRequestRepository;
import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The barcode side of an item stock movement for an Ecommerce product: product units built (with the pieces fitted
 * into them) or taken out of stock. {@link #prepare} checks everything and locks the units involved before any stock
 * moves; {@link #apply} records the change once the stock has moved. Both run in the movement's transaction.
 */
@Component
@RequiredArgsConstructor
public class ProductUnitMovements {

    private static final String FIELD = "productUnits";

    private final BarcodeRegistry registry;
    private final BarcodeRepository barcodes;
    private final ProductApprovalRequestRepository approvals;

    /** A checked change: the units involved are locked and nothing has been written yet. */
    public static final class Plan {
        private final ItemStockMovementDTOs.ProductUnitChange change;
        private final boolean awaitingApproval;
        private final List<String> newUnitCodes = new ArrayList<>();
        private final List<List<Barcode>> partsOfNewUnits = new ArrayList<>();
        private final List<Barcode> removedUnits = new ArrayList<>();
        private final Map<Long, List<Barcode>> partsOfRemovedUnits = new HashMap<>();

        private Plan(ItemStockMovementDTOs.ProductUnitChange change, boolean awaitingApproval) {
            this.change = change;
            this.awaitingApproval = awaitingApproval;
        }

        /** Pieces of the item this change takes from stock by barcode, counted per stock entry. */
        public Map<Long, BigDecimal> fittedByStock(Long itemId) {
            return countByStock(partsOfNewUnits.stream().flatMap(List::stream), itemId);
        }

        /** Pieces of the item this change returns to stock by barcode, counted per stock entry. */
        public Map<Long, BigDecimal> returnedByStock(Long itemId) {
            if (!change.isReturnParts()) {
                return Map.of();
            }
            return countByStock(partsOfRemovedUnits.values().stream().flatMap(List::stream), itemId);
        }

        private static Map<Long, BigDecimal> countByStock(java.util.stream.Stream<Barcode> parts, Long itemId) {
            Map<Long, BigDecimal> units = new LinkedHashMap<>();
            parts.filter(part -> part.getItem().getId().equals(itemId))
                    .forEach(part -> units.merge(part.getStock().getId(), BigDecimal.ONE, BigDecimal::add));
            return units;
        }
    }

    /**
     * Checks the change against the movement's lines and locks the units it names. Throws (and nothing changes) when
     * a code is malformed, repeated, taken or not usable, when the counts don't match the product's stock change, or
     * when the pieces named don't match the item quantities moved.
     */
    public Plan prepare(ItemStockMovementDTOs.ProductUnitChange change, String reference, List<ItemStockMovementDTOs.Line> lines) {
        Long productId = change.getProductId();
        if (!reference.equals("product:" + productId)) {
            throw ApiErrors.fieldError(FIELD + ".productId", "Product units must belong to the movement's product (" + reference + ")");
        }
        List<ItemStockMovementDTOs.NewUnit> added = Optional.ofNullable(change.getAdded()).orElse(List.of());
        List<String> removed = Optional.ofNullable(change.getRemoved()).orElse(List.of());
        int before = change.getStockBefore();
        int after = change.getStockAfter();

        // Every new unit needs its barcode; units taken out are named unless they never had one
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        if (added.size() != Math.max(0, after - before)) {
            errors.add(ApiErrors.error(FIELD + ".added", "Enter a barcode for each of the " + Math.max(0, after - before)
                    + " new product units (" + added.size() + " entered)"));
        }
        if (after >= before && !removed.isEmpty()) {
            errors.add(ApiErrors.error(FIELD + ".removed", "No product units are taken out of stock by this change"));
        }

        Set<String> seen = new HashSet<>();
        Plan plan = new Plan(change, change.isAwaitingApproval() || !approved(productId));
        List<List<String>> partCodes = new ArrayList<>();
        List<List<Long>> partItems = new ArrayList<>();
        for (int i = 0; i < added.size(); i++) {
            ItemStockMovementDTOs.NewUnit unit = added.get(i);
            plan.newUnitCodes.add(code(unit.getBarcode(), FIELD + ".added[" + i + "].barcode", seen, errors));
            List<String> codes = new ArrayList<>();
            List<Long> itemIds = new ArrayList<>();
            List<ItemStockMovementDTOs.Part> parts = Optional.ofNullable(unit.getParts()).orElse(List.of());
            for (int j = 0; j < parts.size(); j++) {
                List<String> pieces = parts.get(j).getBarcodes();
                for (int k = 0; k < pieces.size(); k++) {
                    codes.add(code(pieces.get(k), FIELD + ".added[" + i + "].parts[" + j + "].barcodes[" + k + "]", seen, errors));
                    itemIds.add(parts.get(j).getItemId());
                }
            }
            partCodes.add(codes);
            partItems.add(itemIds);
        }
        List<String> removedCodes = new ArrayList<>();
        for (int i = 0; i < removed.size(); i++) {
            removedCodes.add(code(removed.get(i), FIELD + ".removed[" + i + "]", seen, errors));
        }
        ApiErrors.throwIfAny(errors);

        registry.requireFree(plan.newUnitCodes, FIELD + ".added");
        preparePieces(plan, partCodes, partItems);
        prepareRemovals(plan, removedCodes, productId, before, after);
        requireMatchingLines(plan, lines);
        return plan;
    }

    /** Writes the change: new units are registered with their pieces fitted, removed units leave stock. */
    public void apply(Plan plan, UUID movementId, String performedBy) {
        ItemStockMovementDTOs.ProductUnitChange change = plan.change;
        BarcodeRegistry.Actor actor = BarcodeRegistry.Actor.service(performedBy);
        if (!plan.newUnitCodes.isEmpty()) {
            Map<String, List<Barcode>> partsByUnit = new LinkedHashMap<>();
            for (int i = 0; i < plan.newUnitCodes.size(); i++) {
                partsByUnit.put(plan.newUnitCodes.get(i), plan.partsOfNewUnits.get(i));
            }
            registry.registerProductUnits(partsByUnit, change.getProductId(), change.getProductTitle(), plan.awaitingApproval,
                    movementId, actor);
        }
        if (!plan.removedUnits.isEmpty()) {
            List<Barcode> parts = plan.partsOfRemovedUnits.values().stream().flatMap(List::stream).toList();
            registry.removeProductUnits(plan.removedUnits, parts, change.isWithdrawRegistration(), change.isReturnParts(), movementId,
                    actor, change.isWithdrawRegistration() ? "Product not approved" : null);
        }
    }

    /** {@link #heldUnitCodes} without locking the units, for reads. */
    public List<String> readHeldUnitCodes(Long productId) {
        return barcodes.findProductUnitCodes(BarcodeUnitType.PRODUCT_UNIT, productId, BarcodeStatus.HELD);
    }

    /** Barcodes of the product's units still held as stock, oldest first. */
    public List<String> heldUnitCodes(Long productId) {
        return barcodes.findProductUnitsForUpdate(BarcodeUnitType.PRODUCT_UNIT, productId, BarcodeStatus.HELD).stream()
                .map(Barcode::getCode).toList();
    }

    // Products created before the approval workflow have no request and count as approved
    private boolean approved(Long productId) {
        return approvals.findFirstByEcommerceProductIdOrderByRequestedAtDescIdDesc(productId).isEmpty()
                || approvals.existsByEcommerceProductIdAndStatus(productId, ProductApprovalStatus.APPROVED);
    }

    // Pieces fitted into new units must be item units in stock, of the item they are listed under
    private void preparePieces(Plan plan, List<List<String>> partCodes, List<List<Long>> partItems) {
        List<String> all = partCodes.stream().flatMap(List::stream).toList();
        Map<String, Barcode> byCode = all.isEmpty() ? Map.of() : registry.lockLive(all, FIELD + ".added").stream()
                .collect(Collectors.toMap(Barcode::getCode, barcode -> barcode));
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        for (int i = 0; i < partCodes.size(); i++) {
            List<Barcode> parts = new ArrayList<>();
            for (int k = 0; k < partCodes.get(i).size(); k++) {
                Barcode part = byCode.get(partCodes.get(i).get(k));
                Long itemId = partItems.get(i).get(k);
                String field = FIELD + ".added[" + i + "].parts";
                if (part.getUnitType() != BarcodeUnitType.ITEM_UNIT) {
                    errors.add(ApiErrors.error(field, "Barcode " + part.getCode() + " is a product unit, not a component piece"));
                } else if (!part.getItem().getId().equals(itemId)) {
                    errors.add(ApiErrors.error(field, "Barcode " + part.getCode() + " is a piece of " + part.getItem().getName()
                            + ", not of the component it is listed under"));
                } else if (part.getStatus() != BarcodeStatus.IN_STOCK) {
                    errors.add(ApiErrors.error(field, "Barcode " + part.getCode() + " (" + part.getItem().getName() + ") is not in stock: it is "
                            + BarcodeRegistry.describe(part.getStatus())));
                }
                parts.add(part);
            }
            plan.partsOfNewUnits.add(parts);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.BARCODE_NOT_AVAILABLE, HttpStatus.CONFLICT, errors);
        }
    }

    // Units taken out must be this product's units in stock; units without a barcode (built before barcodes were
    // recorded) are the only ones that can go unnamed
    private void prepareRemovals(Plan plan, List<String> codes, Long productId, int before, int after) {
        if (codes.isEmpty() && before <= after) {
            return;
        }
        List<Barcode> units = codes.isEmpty() ? List.of() : registry.lockLive(codes, FIELD + ".removed");
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Barcode unit = units.get(i);
            if (unit.getUnitType() != BarcodeUnitType.PRODUCT_UNIT || !productId.equals(unit.getEcommerceProductId())) {
                errors.add(ApiErrors.error(FIELD + ".removed[" + i + "]", "Barcode " + unit.getCode() + " is not a unit of this product"));
            } else if (!BarcodeStatus.HELD.contains(unit.getStatus())) {
                errors.add(ApiErrors.error(FIELD + ".removed[" + i + "]", "Barcode " + unit.getCode() + " is not in stock: it is "
                        + BarcodeRegistry.describe(unit.getStatus())));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.BARCODE_NOT_AVAILABLE, HttpStatus.CONFLICT, errors);
        }
        long held = barcodes.countByUnitTypeAndEcommerceProductIdAndStatusIn(BarcodeUnitType.PRODUCT_UNIT, productId, BarcodeStatus.HELD);
        int taken = before - after;
        int unnamedAllowed = (int) Math.max(0, before - held);
        if (units.size() > taken || units.size() < taken - unnamedAllowed) {
            String message = "Enter the barcode of each of the " + (taken - Math.min(taken, unnamedAllowed))
                    + (unnamedAllowed > 0 ? " to " + taken : "") + " product units taken out of stock (" + units.size() + " entered)";
            throw new ApiException(message, ErrorCodes.BARCODES_REQUIRED, HttpStatus.BAD_REQUEST,
                    List.of(ApiErrors.error(FIELD + ".removed", message)));
        }
        plan.removedUnits.addAll(units);
        if (!units.isEmpty()) {
            barcodes.findPartsForUpdate(units.stream().map(Barcode::getId).toList())
                    .forEach(part -> plan.partsOfRemovedUnits.computeIfAbsent(part.getFittedInto().getId(), id -> new ArrayList<>()).add(part));
        }
    }

    // Pieces named can't exceed what the lines move for their item, in the right direction
    private static void requireMatchingLines(Plan plan, List<ItemStockMovementDTOs.Line> lines) {
        Map<Long, BigDecimal> quantities = new HashMap<>();
        lines.forEach(line -> quantities.put(line.getItemId(), line.getQuantity()));
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        countByItem(plan.partsOfNewUnits.stream().flatMap(List::stream).toList()).forEach((item, count) -> {
            BigDecimal moved = quantities.getOrDefault(item.getId(), BigDecimal.ZERO);
            if (moved.compareTo(BigDecimal.valueOf(count)) < 0) {
                errors.add(ApiErrors.error(FIELD + ".added", count + " pieces of " + item.getName() + " are fitted but only "
                        + Quantities.plain(moved.max(BigDecimal.ZERO)) + " are taken from stock"));
            }
        });
        if (plan.change.isReturnParts()) {
            countByItem(plan.partsOfRemovedUnits.values().stream().flatMap(List::stream).toList()).forEach((item, count) -> {
                BigDecimal moved = quantities.getOrDefault(item.getId(), BigDecimal.ZERO).negate();
                if (moved.compareTo(BigDecimal.valueOf(count)) < 0) {
                    errors.add(ApiErrors.error(FIELD + ".removed", count + " pieces of " + item.getName() + " come out of the units but only "
                            + Quantities.plain(moved.max(BigDecimal.ZERO)) + " are returned to stock"));
                }
            });
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.INVALID_MOVEMENT_LINES, HttpStatus.BAD_REQUEST, errors);
        }
    }

    private static Map<Item, Long> countByItem(List<Barcode> parts) {
        Map<Item, Long> counts = new TreeMap<>(Comparator.comparing(Item::getId));
        parts.forEach(part -> counts.merge(part.getItem(), 1L, Long::sum));
        return counts;
    }

    private static String code(String raw, String field, Set<String> seen, List<ApiResponse.ApiErrorItem> errors) {
        String code = BarcodeCodes.normalize(raw);
        if (code == null) {
            errors.add(ApiErrors.error(field, "Barcode is required"));
        } else if (!BarcodeCodes.isValid(code)) {
            errors.add(ApiErrors.error(field, "Barcode " + code + " " + ValidationPatterns.BARCODE_MESSAGE));
        } else if (!seen.add(code)) {
            errors.add(ApiErrors.error(field, "Barcode " + code + " is entered more than once"));
        }
        return code;
    }
}
