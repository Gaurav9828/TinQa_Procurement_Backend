package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.barcode.entity.Barcode;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import com.tinqa.procurement.barcode.repository.BarcodeRepository;
import com.tinqa.procurement.barcode.service.BarcodeCodes;
import com.tinqa.procurement.barcode.service.BarcodeRegistry;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picking the product units a demo takes, by barcode, while it is planned: the units of a product that can be picked
 * for the demo's days, one unit by its barcode, and the check of the plan's barcodes. A unit is picked only while it is
 * in stock and no other planned or running demo holds it on overlapping days. See {@link DemoUnitService} for handing
 * units out and back.
 */
@Component
@RequiredArgsConstructor
public class DemoUnitPlanner {

    // Most units of one product offered for picking at once
    private static final int MAX_OPTIONS = 500;

    private final DemoUnitRepository unitRepository;
    private final BarcodeRepository barcodeRepository;

    /**
     * The product's units in stock, oldest first, each saying whether it can be picked for a demo on these days (one
     * held by another demo then can't). {@code demoId}: the demo being changed, whose own units don't count as held.
     */
    @Transactional(readOnly = true)
    public List<ClientDemoDTOs.UnitOption> options(Long productId, LocalDate start, int days, Long demoId) {
        List<Barcode> units = barcodeRepository.findByUnitTypeAndEcommerceProductIdAndStatusOrderByIdAsc(BarcodeUnitType.PRODUCT_UNIT,
                productId, BarcodeStatus.IN_STOCK, PageRequest.of(0, MAX_OPTIONS));
        Map<Long, String> heldBy = heldBy(units, demoId, start, days);
        return units.stream().map(unit -> option(unit, heldBy.get(unit.getId()))).toList();
    }

    /** One unit by its barcode (typed or scanned), with its product; 404 when no unit carries it. */
    @Transactional(readOnly = true)
    public ClientDemoDTOs.UnitOption lookup(String rawCode, LocalDate start, int days, Long demoId) {
        String code = BarcodeCodes.normalize(rawCode);
        Barcode unit = code == null ? null : barcodeRepository.findLiveByCode(code).orElse(null);
        if (unit == null) {
            throw ApiErrors.of(HttpStatus.NOT_FOUND, ErrorCodes.BARCODE_NOT_FOUND, "No unit carries barcode " + (code == null ? "" : code) + ".");
        }
        if (unit.getUnitType() != BarcodeUnitType.PRODUCT_UNIT) {
            throw ApiErrors.of(HttpStatus.CONFLICT, ErrorCodes.DEMO_UNIT_NOT_AVAILABLE, "Barcode " + code
                    + " is a component piece, not a product unit. Scan the barcode on the product.");
        }
        return option(unit, unit.getStatus() == BarcodeStatus.IN_STOCK ? heldBy(List.of(unit), demoId, start, days).get(unit.getId()) : null);
    }

    /**
     * Makes the demo's units match the barcodes of each product (called while the plan is applied, after its dates).
     * Returns each product's number of units, which is its number of barcodes; a product without barcodes (listed
     * before units were picked) keeps its number while it is unchanged. Adds what changed to {@code changes}.
     */
    Map<Long, Integer> applyPlan(ClientDemo demo, List<ClientDemoDTOs.ProductRequest> requests, List<String> changes, boolean isNew) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        Map<String, Long> productOfCode = new LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        Map<Long, Integer> quantities = new HashMap<>();
        Map<Long, Integer> previous = demo.getProducts().stream().collect(Collectors.toMap(DemoProduct::getProductId, DemoProduct::getQuantity));
        Set<Long> productsWithUnits = demo.getUnits().stream().map(DemoUnit::getProductId).collect(Collectors.toSet());
        for (int i = 0; i < requests.size(); i++) {
            ClientDemoDTOs.ProductRequest request = requests.get(i);
            names.put(request.getProductId(), request.getProductName().trim());
            String field = "products[" + i + "].barcodes";
            List<String> codes = BarcodeCodes.normalizeAll(request.getBarcodes(), field, errors);
            if (codes.isEmpty()) {
                boolean legacy = !isNew && Objects.equals(previous.get(request.getProductId()), request.getQuantity())
                        && !productsWithUnits.contains(request.getProductId());
                if (!legacy && (request.getBarcodes() == null || request.getBarcodes().isEmpty())) {
                    errors.add(ApiErrors.error(field, "Pick the barcode of each unit of " + request.getProductName().trim() + " you take."));
                }
                quantities.put(request.getProductId(), request.getQuantity());
                continue;
            }
            for (String code : codes) {
                if (productOfCode.putIfAbsent(code, request.getProductId()) != null) {
                    errors.add(ApiErrors.error(field, "Barcode " + code + " is picked twice."));
                }
            }
            quantities.put(request.getProductId(), codes.size());
        }
        ApiErrors.throwIfAny(errors);

        // Units handed out stay on the demo's record
        Map<String, DemoUnit> current = demo.getUnits().stream().collect(Collectors.toMap(DemoUnit::getCode, Function.identity()));
        for (DemoUnit unit : demo.getUnits()) {
            if (unit.getStatus().handedOut() && !unit.getProductId().equals(productOfCode.get(unit.getCode()))) {
                errors.add(ApiErrors.error("products", "Unit " + unit.getCode() + " was handed out, so it stays on the demo."));
            }
        }
        ApiErrors.throwIfAny(errors);

        // Units to reserve: new ones, and those only reserved so far (checked again: they may have been sold since)
        List<String> toCheck = productOfCode.keySet().stream()
                .filter(code -> current.get(code) == null || !current.get(code).getStatus().handedOut()).toList();
        Map<String, Barcode> barcodes = toCheck.isEmpty() ? Map.of() : barcodeRepository.findLiveByCodes(toCheck).stream()
                .collect(Collectors.toMap(Barcode::getCode, Function.identity()));
        Map<Long, String> heldBy = heldBy(barcodes.values(), demo.getId(), demo.getStartDate(), demo.getNumberOfDays());
        for (String code : toCheck) {
            Barcode barcode = barcodes.get(code);
            Long productId = productOfCode.get(code);
            String product = names.get(productId);
            if (barcode == null) {
                errors.add(ApiErrors.error("products", "No unit carries barcode " + code + "."));
            } else if (barcode.getUnitType() != BarcodeUnitType.PRODUCT_UNIT || !productId.equals(barcode.getEcommerceProductId())) {
                errors.add(ApiErrors.error("products", "Barcode " + code + " is not a unit of " + product + "."));
            } else if (barcode.getStatus() != BarcodeStatus.IN_STOCK) {
                errors.add(ApiErrors.error("products", "Unit " + code + " of " + product + " is not in stock: it is "
                        + BarcodeRegistry.describe(barcode.getStatus()) + ". Pick another unit."));
            } else if (heldBy.containsKey(barcode.getId())) {
                errors.add(ApiErrors.error("products", "Unit " + code + " of " + product + " is taken by " + heldBy.get(barcode.getId())
                        + " on these days. Pick another unit."));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.DEMO_UNIT_NOT_AVAILABLE, HttpStatus.CONFLICT, errors);
        }

        List<String> added = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        demo.getUnits().removeIf(unit -> {
            boolean drop = !productOfCode.containsKey(unit.getCode());
            if (drop) {
                dropped.add(unit.getCode());
            }
            return drop;
        });
        for (String code : toCheck) {
            DemoUnit unit = current.get(code);
            if (unit == null) {
                demo.getUnits().add(DemoUnit.builder().demo(demo).barcodeId(barcodes.get(code).getId()).code(code)
                        .productId(productOfCode.get(code)).status(DemoUnitStatus.RESERVED).build());
                added.add(code);
            } else if (unit.getStatus() == DemoUnitStatus.NOT_TAKEN) {
                unit.setStatus(DemoUnitStatus.RESERVED);
                added.add(code);
            }
        }
        if (!isNew && !added.isEmpty()) {
            changes.add("units picked: " + String.join(", ", added));
        }
        if (!isNew && !dropped.isEmpty()) {
            changes.add("units dropped: " + String.join(", ", dropped));
        }
        return quantities;
    }

    // Barcode id → the reference of another demo holding the unit on [start, start + days - 1]
    private Map<Long, String> heldBy(Collection<Barcode> units, Long demoId, LocalDate start, int days) {
        if (units.isEmpty() || start == null) {
            return Map.of();
        }
        Set<Long> ids = new HashSet<>(units.stream().map(Barcode::getId).toList());
        Map<Long, String> held = new HashMap<>();
        unitRepository.findHolders(ids, demoId == null ? -1L : demoId, start, start.plusDays(Math.max(days, 1) - 1L))
                .forEach(holder -> held.putIfAbsent(holder.getBarcodeId(), ClientDemo.reference(holder.getDemoId())));
        return held;
    }

    private static ClientDemoDTOs.UnitOption option(Barcode unit, String heldBy) {
        boolean inStock = unit.getStatus() == BarcodeStatus.IN_STOCK;
        String note = !inStock ? "Not in stock: " + BarcodeRegistry.describe(unit.getStatus())
                : heldBy != null ? "Taken by " + heldBy + " on these days" : null;
        return ClientDemoDTOs.UnitOption.builder()
                .barcode(unit.getCode())
                .productId(unit.getEcommerceProductId())
                .productTitle(unit.getProductTitle())
                .status(unit.getStatus().name())
                .statusLabel(BarcodeRegistry.describe(unit.getStatus()))
                .inStockSince(unit.getLockedAt())
                .available(inStock && heldBy == null)
                .heldBy(heldBy)
                .note(note)
                .build();
    }
}
