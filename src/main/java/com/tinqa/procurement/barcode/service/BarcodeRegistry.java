package com.tinqa.procurement.barcode.service;

import com.tinqa.procurement.barcode.entity.Barcode;
import com.tinqa.procurement.barcode.entity.BarcodeEvent;
import com.tinqa.procurement.barcode.enums.BarcodeEventType;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import com.tinqa.procurement.barcode.repository.BarcodeEventRepository;
import com.tinqa.procurement.barcode.repository.BarcodeRepository;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.stock.entity.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every change to a barcode goes through here, inside the caller's transaction, and leaves a {@link BarcodeEvent}.
 * Callers hold the lock of what the barcodes belong to (the stock entry, or the product's movement reference).
 */
@Component
@RequiredArgsConstructor
public class BarcodeRegistry {

    private static final Set<BarcodeStatus> PENDING_ONLY = EnumSet.of(BarcodeStatus.PENDING);
    private static final Set<BarcodeStatus> CHANGE_STATUSES = EnumSet.of(BarcodeStatus.PENDING, BarcodeStatus.WITHDRAWAL_PENDING);

    private final BarcodeRepository barcodes;
    private final BarcodeEventRepository events;
    private final BarcodeWriter writer;

    /** Someone acting on barcodes: an admin, or a service acting for an admin known only by name. */
    public record Actor(Long id, String name) {
        public static Actor of(User user) {
            return new Actor(user.getId(), user.getUsername());
        }

        public static Actor service(String name) {
            return new Actor(null, name);
        }
    }

    // ------------------------------------------------------------------ codes

    /**
     * The codes, normalized, after checking there are exactly {@code expected} valid, distinct ones (400 otherwise).
     * {@code what} names the pieces in the message, e.g. "passed unit".
     */
    public List<String> requireCodes(List<String> raw, int expected, String field, String what) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        List<String> codes = BarcodeCodes.normalizeAll(raw, field, errors);
        ApiErrors.throwIfAny(errors);
        int given = raw == null ? 0 : raw.size();
        if (given != expected) {
            throw new ApiException(countMessage(expected, given, what), ErrorCodes.BARCODES_REQUIRED, HttpStatus.BAD_REQUEST,
                    List.of(ApiErrors.error(field, countMessage(expected, given, what))));
        }
        return codes;
    }

    /** Refuses (409 BARCODE_TAKEN, one error per code) codes already carried by a live unit. */
    public void requireFree(List<String> codes, String field) {
        if (codes.isEmpty()) {
            return;
        }
        Set<String> taken = new HashSet<>(barcodes.findLiveCodes(codes));
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            if (taken.contains(codes.get(i))) {
                errors.add(ApiErrors.error(field + "[" + i + "]", "Barcode " + codes.get(i) + " is already in use"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(errors.size() == 1 ? errors.getFirst().getMessage()
                    : errors.size() + " barcodes are already in use: " + ApiErrors.joinMessages(errors),
                    ErrorCodes.BARCODE_TAKEN, HttpStatus.CONFLICT, errors);
        }
    }

    /**
     * Locks the live units carrying the codes, in the order given. Every code must be known (404 otherwise, one error
     * per code).
     */
    public List<Barcode> lockLive(List<String> codes, String field) {
        Map<String, Barcode> byCode = barcodes.findLiveByCodesForUpdate(codes).stream()
                .collect(Collectors.toMap(Barcode::getCode, Function.identity()));
        List<ApiResponse.ApiErrorItem> missing = new ArrayList<>();
        List<Barcode> found = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            Barcode barcode = byCode.get(codes.get(i));
            if (barcode == null) {
                missing.add(ApiErrors.error(field + "[" + i + "]", "Barcode " + codes.get(i) + " is not registered"));
            } else {
                found.add(barcode);
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(missing), ErrorCodes.BARCODE_NOT_FOUND, HttpStatus.NOT_FOUND, missing);
        }
        return found;
    }

    // ------------------------------------------------------------------ item units of a stock entry

    /** Registers the codes as new passed units of the entry, waiting for the entry's (or its change's) approval. */
    public void registerStockUnits(Stock stock, List<String> codes, String field, Actor actor, String note) {
        requireFree(codes, field);
        writer.register(stockUnits(stock, codes, BarcodeStatus.PENDING), false, null, note, actor);
    }

    /**
     * Admin L2 approved the entry or its change: added units come into stock and are locked, units withdrawn by a
     * correction leave for good.
     */
    public void approveStockUnits(Stock stock, Actor approver) {
        writer.apply(BarcodeWriter.Change.of(writer.lockStockUnits(stock.getId(), BarcodeStatus.PENDING), BarcodeStatus.IN_STOCK,
                BarcodeEventType.APPROVED, approver).locking());
        writer.apply(BarcodeWriter.Change.of(writer.lockStockUnits(stock.getId(), BarcodeStatus.WITHDRAWAL_PENDING), BarcodeStatus.VOID,
                BarcodeEventType.WITHDRAWAL_APPROVED, approver));
    }

    /** Admin L2 rejected the entry or its change: added units are voided, withdrawn ones come back into stock. */
    public void rejectStockUnits(Stock stock, Actor approver, String reason) {
        writer.apply(BarcodeWriter.Change.of(writer.lockStockUnits(stock.getId(), BarcodeStatus.PENDING), BarcodeStatus.VOID,
                BarcodeEventType.VOIDED, approver).noted(reason));
        writer.apply(BarcodeWriter.Change.of(writer.lockStockUnits(stock.getId(), BarcodeStatus.WITHDRAWAL_PENDING), BarcodeStatus.IN_STOCK,
                BarcodeEventType.WITHDRAWAL_REJECTED, approver).noted(reason));
    }

    /** Takes the entry's units carrying the codes out of stock (damaged, lost...). Each must be in stock in this entry. */
    public void removeStockUnits(Stock stock, List<String> codes, String field, Actor actor, String reason) {
        List<Barcode> units = lockLive(codes, field);
        requireInEntry(stock, units, codes, field, EnumSet.of(BarcodeStatus.IN_STOCK), "in stock");
        writer.apply(BarcodeWriter.Change.of(ids(units), BarcodeStatus.REMOVED, BarcodeEventType.REMOVED, actor)
                .noted(reason).withStatusNote(reason));
    }

    /**
     * A count correction takes the entry's units carrying the codes off. Units not approved yet are simply voided;
     * approved units wait for Admin L2 (WITHDRAWAL_PENDING), who may put them back. Units awaiting approval go first,
     * so the entry's usable units stay exactly its approved ones.
     */
    public void withdrawStockUnits(Stock stock, List<String> codes, String field, Actor actor, String reason) {
        List<Barcode> units = lockLive(codes, field);
        requireInEntry(stock, units, codes, field, EnumSet.of(BarcodeStatus.PENDING, BarcodeStatus.IN_STOCK),
                "in stock or awaiting approval");
        long pendingTaken = units.stream().filter(b -> b.getStatus() == BarcodeStatus.PENDING).count();
        boolean takesApproved = units.stream().anyMatch(b -> b.getStatus() == BarcodeStatus.IN_STOCK);
        if (takesApproved && pendingTaken < barcodes.countByStockIdAndStatusIn(stock.getId(), PENDING_ONLY)) {
            throw ApiErrors.fieldError(field, "Take off the units still awaiting approval before approved ones");
        }
        writer.apply(BarcodeWriter.Change.of(ids(units, BarcodeStatus.PENDING), BarcodeStatus.VOID, BarcodeEventType.VOIDED, actor)
                .noted(reason));
        writer.apply(BarcodeWriter.Change.of(ids(units, BarcodeStatus.IN_STOCK), BarcodeStatus.WITHDRAWAL_PENDING,
                BarcodeEventType.WITHDRAWAL_REQUESTED, actor).noted(reason));
    }

    /**
     * Available units of the entry without a barcode: stock received before barcodes were recorded. Only these can be
     * used, removed or corrected without naming a barcode.
     */
    public int untrackedUnits(Stock stock) {
        long tracked = barcodes.countByStockIdAndStatusIn(stock.getId(), EnumSet.of(BarcodeStatus.IN_STOCK));
        return stock.getAvailableUnits().subtract(BigDecimal.valueOf(tracked)).max(BigDecimal.ZERO).intValue();
    }

    /** Passed units of the entry counted without a barcode (received before barcodes were recorded). */
    public int untrackedPassedUnits(Stock stock) {
        long tracked = barcodes.countByStockIdAndStatusIn(stock.getId(), BarcodeStatus.COUNTED);
        return stock.getUnitsPassedTest().subtract(BigDecimal.valueOf(tracked)).max(BigDecimal.ZERO).intValue();
    }

    /** Whether units of the entry wait for Admin L2: added ones not approved yet, or approved ones being taken off. */
    public boolean hasUnitsAwaitingDecision(Stock stock) {
        return barcodes.countByStockIdAndStatusIn(stock.getId(), CHANGE_STATUSES) > 0;
    }

    /** Untracked available units of each entry (see {@link #untrackedUnits(Stock)}), keyed by stock id. */
    public Map<Long, BigDecimal> untrackedUnits(Collection<Stock> stocks) {
        if (stocks.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> tracked = barcodes.countByStock(stocks.stream().map(Stock::getId).toList(), BarcodeStatus.IN_STOCK)
                .stream()
                .collect(Collectors.toMap(BarcodeRepository.StockCount::getStockId, BarcodeRepository.StockCount::getUnits));
        Map<Long, BigDecimal> untracked = new HashMap<>();
        for (Stock stock : stocks) {
            BigDecimal units = stock.getAvailableUnits().subtract(BigDecimal.valueOf(tracked.getOrDefault(stock.getId(), 0L)));
            untracked.put(stock.getId(), units.max(BigDecimal.ZERO));
        }
        return untracked;
    }

    /** Registers codes for untracked units of an approved entry; they are in stock and locked straight away. */
    public void assignStockUnits(Stock stock, List<String> codes, String field, Actor actor, String note) {
        requireFree(codes, field);
        writer.register(stockUnits(stock, codes, BarcodeStatus.IN_STOCK), true, null, note, actor);
    }

    private static List<BarcodeWriter.NewUnit> stockUnits(Stock stock, List<String> codes, BarcodeStatus status) {
        return codes.stream().map(code -> new BarcodeWriter.NewUnit(code, BarcodeUnitType.ITEM_UNIT, status, stock.getItem().getId(),
                stock.getId(), null, null)).toList();
    }

    private void requireInEntry(Stock stock, List<Barcode> units, List<String> codes, String field,
                                Set<BarcodeStatus> allowed, String allowedLabel) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Barcode barcode = units.get(i);
            if (barcode.getStock() == null || !barcode.getStock().getId().equals(stock.getId())) {
                errors.add(ApiErrors.error(field + "[" + i + "]", "Barcode " + codes.get(i) + " does not belong to stock "
                        + stock.getStockIdentityNumber()));
            } else if (!allowed.contains(barcode.getStatus())) {
                errors.add(ApiErrors.error(field + "[" + i + "]", "Barcode " + codes.get(i) + " is not " + allowedLabel
                        + " (it is " + describe(barcode.getStatus()) + ")"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.BARCODE_NOT_AVAILABLE, HttpStatus.CONFLICT, errors);
        }
    }

    // ------------------------------------------------------------------ product units

    /**
     * New units of an Ecommerce product, each with the pieces (item units in stock) fitted into it. Units of a product
     * that isn't approved yet wait for its approval; others are in stock and locked at once.
     *
     * @param partsByUnitCode the pieces of each new unit, by the unit's code
     */
    public void registerProductUnits(Map<String, List<Barcode>> partsByUnitCode, Long productId, String productTitle,
                                     boolean awaitingApproval, UUID movementId, Actor actor) {
        List<BarcodeWriter.NewUnit> units = partsByUnitCode.keySet().stream()
                .map(code -> new BarcodeWriter.NewUnit(code, BarcodeUnitType.PRODUCT_UNIT,
                        awaitingApproval ? BarcodeStatus.PENDING : BarcodeStatus.IN_STOCK, null, null, productId, productTitle))
                .toList();
        Map<String, Long> unitIds = writer.register(units, !awaitingApproval, movementId, null, actor);
        Map<Long, Long> fittedInto = new LinkedHashMap<>();
        partsByUnitCode.forEach((code, parts) -> parts.forEach(part -> fittedInto.put(part.getId(), unitIds.get(code))));
        writer.apply(BarcodeWriter.Change.fitting(fittedInto, movementId, actor));
    }

    /**
     * Product units leave stock. {@code withdraw}: their registrations are withdrawn (the product was rejected), which
     * frees the codes; otherwise they are removed and kept. Their pieces come back into item stock ({@code returnParts}),
     * or are removed with them (scrapped).
     */
    public void removeProductUnits(List<Barcode> units, List<Barcode> parts, boolean withdraw, boolean returnParts,
                                   UUID movementId, Actor actor, String note) {
        if (returnParts) {
            writer.apply(BarcodeWriter.Change.of(ids(parts), BarcodeStatus.IN_STOCK, BarcodeEventType.RETURNED, actor)
                    .clearingFit().inMovement(movementId).noted(note));
        } else {
            writer.apply(BarcodeWriter.Change.of(ids(parts), BarcodeStatus.REMOVED, BarcodeEventType.REMOVED, actor)
                    .inMovement(movementId).noted(note).withStatusNote(note));
        }
        BarcodeWriter.Change change = withdraw
                ? BarcodeWriter.Change.of(ids(units), BarcodeStatus.VOID, BarcodeEventType.VOIDED, actor)
                : BarcodeWriter.Change.of(ids(units), BarcodeStatus.REMOVED, BarcodeEventType.REMOVED, actor);
        writer.apply(change.inMovement(movementId).noted(note).withStatusNote(note));
    }

    /** The product was approved: its units come into stock and are locked. */
    public void approveProductUnits(Long productId, Actor approver) {
        writer.apply(BarcodeWriter.Change.of(writer.lockProductUnits(productId, BarcodeStatus.PENDING), BarcodeStatus.IN_STOCK,
                BarcodeEventType.APPROVED, approver).locking());
    }

    // ------------------------------------------------------------------ reversal of a movement

    /**
     * Refuses (409 REVERSAL_NOT_POSSIBLE) when a barcode the movement changed has changed again since (or a code it
     * would bring back is in use again), so reversing the movement would undo someone else's work.
     */
    public void requireReversible(UUID movementId) {
        if (!writer.unchangedSince(movementId)) {
            throw ApiErrors.conflict(ErrorCodes.REVERSAL_NOT_POSSIBLE, "Movement " + movementId
                    + " cannot be reversed: barcodes it changed have changed since. Reverse the later change first.");
        }
    }

    /** Undoes what the movement did to barcodes. Call {@link #requireReversible} first. */
    public void reverse(UUID movementId, Actor actor) {
        writer.reverse(movementId, actor);
    }

    // ------------------------------------------------------------------ corrections

    /** Gives a barcode a new code (a Super Admin's correction). */
    public void correctCode(Barcode barcode, String newCode, Actor actor, String reason) {
        String oldCode = barcode.getCode();
        barcode.setCode(newCode);
        barcodes.saveAndFlush(barcode);
        events.save(BarcodeEvent.builder()
                .barcode(barcode)
                .eventType(BarcodeEventType.CODE_CORRECTED)
                .fromStatus(barcode.getStatus())
                .toStatus(barcode.getStatus())
                .oldCode(oldCode)
                .newCode(newCode)
                .note(reason)
                .actorId(actor.id())
                .actorName(actor.name())
                .build());
    }

    // ------------------------------------------------------------------ helpers

    private static List<Long> ids(Collection<Barcode> units) {
        return units.stream().map(Barcode::getId).toList();
    }

    private static List<Long> ids(Collection<Barcode> units, BarcodeStatus status) {
        return units.stream().filter(unit -> unit.getStatus() == status).map(Barcode::getId).toList();
    }

    private static String countMessage(int expected, int given, String what) {
        if (expected == 0) {
            return "No barcodes are needed here (" + given + " entered)";
        }
        return expected == 1
                ? "Enter the barcode of the " + what + (given == 0 ? "" : " (" + given + " entered)")
                : "Enter one barcode for each of the " + expected + " " + what + "s (" + given + " entered)";
    }

    /** e.g. "in stock", "fitted in a product". */
    public static String describe(BarcodeStatus status) {
        return switch (status) {
            case PENDING -> "awaiting approval";
            case IN_STOCK -> "in stock";
            case FITTED -> "fitted in a product";
            case REMOVED -> "removed from stock";
            case WITHDRAWAL_PENDING -> "being taken off stock";
            case VOID -> "void";
        };
    }
}
