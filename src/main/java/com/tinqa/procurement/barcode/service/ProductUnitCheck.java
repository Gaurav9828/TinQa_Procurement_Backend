package com.tinqa.procurement.barcode.service;

import com.tinqa.procurement.barcode.repository.BarcodeRepository;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checks a stock entry draft of product units (each unit's barcode and the pieces fitted into it) before it is saved,
 * with the rules {@link ProductUnitMovements#prepare} applies on submit: unit codes must be free, piece codes must be
 * component pieces in stock of the item they are listed under. Reads only (one SQL statement) and locks nothing, so
 * the answer can be stale by submit, where the binding check runs again. Blank, malformed and repeated codes are not
 * reported here: drafts are partly filled, and the caller checks repeats itself.
 */
@Component
@RequiredArgsConstructor
public class ProductUnitCheck {

    private final BarcodeRepository barcodes;

    /** One code of the draft: a new unit's code, or a piece listed under a component (itemId null: not chosen yet). */
    record Entry(String code, boolean unit, Long itemId) {
    }

    @Transactional(readOnly = true)
    public ItemStockMovementDTOs.ProductUnitCheckResponse check(ItemStockMovementDTOs.ProductUnitCheckRequest request) {
        List<Entry> entries = entries(request.getUnits());
        if (entries.isEmpty()) {
            return new ItemStockMovementDTOs.ProductUnitCheckResponse(List.of());
        }
        Set<String> codes = entries.stream().map(Entry::code).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, BarcodeRepository.LiveCode> live = barcodes.findLiveCodeSummaries(codes).stream()
                .collect(Collectors.toMap(BarcodeRepository.LiveCode::getCode, Function.identity(), (a, b) -> a));
        return new ItemStockMovementDTOs.ProductUnitCheckResponse(problems(entries, live));
    }

    /** The live product units carrying the codes (codes matched upper-case); other codes are left out. */
    @Transactional(readOnly = true)
    public ItemStockMovementDTOs.UnitLookupResponse lookup(List<String> codes) {
        Set<String> normalized = Optional.ofNullable(codes).orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalized.isEmpty()) {
            return new ItemStockMovementDTOs.UnitLookupResponse(List.of());
        }
        List<ItemStockMovementDTOs.UnitOfProduct> units = barcodes.findLiveUnitProducts(normalized).stream()
                .filter(unit -> unit.getUnitType() == com.tinqa.procurement.barcode.enums.BarcodeUnitType.PRODUCT_UNIT)
                .map(unit -> new ItemStockMovementDTOs.UnitOfProduct(unit.getCode(), unit.getEcommerceProductId(),
                        com.tinqa.procurement.barcode.enums.BarcodeStatus.HELD.contains(unit.getStatus())))
                .toList();
        return new ItemStockMovementDTOs.UnitLookupResponse(units);
    }

    /** The draft's usable-looking codes, normalized, in order; 400 when it carries more than {@link BarcodeCodes#MAX_CODES}. */
    static List<Entry> entries(List<ItemStockMovementDTOs.DraftUnit> units) {
        List<Entry> entries = new ArrayList<>();
        for (ItemStockMovementDTOs.DraftUnit unit : Optional.ofNullable(units).orElse(List.of())) {
            if (unit == null) {
                continue;
            }
            add(entries, unit.getBarcode(), true, null);
            for (ItemStockMovementDTOs.DraftPart part : Optional.ofNullable(unit.getParts()).orElse(List.of())) {
                if (part == null) {
                    continue;
                }
                for (String piece : Optional.ofNullable(part.getBarcodes()).orElse(List.of())) {
                    add(entries, piece, false, part.getItemId());
                }
            }
        }
        if (entries.size() > BarcodeCodes.MAX_CODES) {
            throw ApiErrors.fieldError("units", "At most " + BarcodeCodes.MAX_CODES + " barcodes can be checked at once ("
                    + entries.size() + " entered)");
        }
        return entries;
    }

    /** One problem per offending code (its first appearance), in the order the codes appear. */
    static List<ItemStockMovementDTOs.CodeProblem> problems(List<Entry> entries, Map<String, BarcodeRepository.LiveCode> live) {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (problems.containsKey(entry.code())) {
                continue;
            }
            String problem = problem(entry, live.get(entry.code()));
            if (problem != null) {
                problems.put(entry.code(), problem);
            }
        }
        return problems.entrySet().stream()
                .map(e -> new ItemStockMovementDTOs.CodeProblem(e.getKey(), e.getValue()))
                .toList();
    }

    private static String problem(Entry entry, BarcodeRepository.LiveCode live) {
        if (entry.unit()) {
            return live == null ? null : BarcodeRegistry.inUseMessage(entry.code());
        }
        if (live == null) {
            return BarcodeRegistry.notRegisteredMessage(entry.code());
        }
        return ProductUnitMovements.pieceProblem(live.getCode(), live.getUnitType(), live.getStatus(), live.getItemId(),
                entry.itemId(), live::getItemName);
    }

    private static void add(List<Entry> entries, String raw, boolean unit, Long itemId) {
        String code = BarcodeCodes.normalize(raw);
        if (BarcodeCodes.isValid(code)) {
            entries.add(new Entry(code, unit, itemId));
        }
    }
}
