package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.barcode.entity.Barcode;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.service.BarcodeCodes;
import com.tinqa.procurement.barcode.service.BarcodeRegistry;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The product units taken to client demos, by barcode, for accountability. Units are picked when the demo is planned
 * (reserved for its days), handed out on the day (they leave stock: the barcode is ON_DEMO and the storefront's
 * sellable stock goes down) and handed back afterwards (in stock again on both sides). A demo is completed or cancelled
 * only once every unit is back; a Super Admin writes off a unit that did not come back.
 *
 * <p>The storefront's stock is changed by the e-commerce service ({@link DemoStockClient}), never inside a transaction
 * here: the units are checked, the storefront moves its stock, then the units move here (checked again under lock).
 * When that last step fails the storefront's move is undone. Picking units while planning is {@link DemoUnitPlanner}.
 */
@Service
@RequiredArgsConstructor
public class DemoUnitService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final ClientDemoService demoService;
    private final BarcodeRegistry registry;
    private final DemoStockClient stockClient;
    private final DemoViews views;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionTemplate transactions;

    // ------------------------------------------------------------------ the workflow's rules

    /** Refuses (409) while units of the demo are out: they are handed back (or written off) first. */
    static void requireAllBack(ClientDemo demo, String action) {
        List<String> out = demo.getUnits().stream().filter(unit -> unit.getStatus() == DemoUnitStatus.OUT).map(DemoUnit::getCode).toList();
        if (!out.isEmpty()) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_UNITS_OUT, demo.reference() + " can't be " + action + " while "
                    + (out.size() == 1 ? "unit " + out.getFirst() + " is" : out.size() + " units are") + " out: "
                    + String.join(", ", out) + ". Hand them back first.");
        }
    }

    /** The demo is over (completed or cancelled): units only reserved were never taken, and no longer held. */
    static void releaseReserved(ClientDemo demo) {
        demo.getUnits().stream().filter(unit -> unit.getStatus() == DemoUnitStatus.RESERVED)
                .forEach(unit -> unit.setStatus(DemoUnitStatus.NOT_TAKEN));
    }

    // ------------------------------------------------------------------ handing out and back

    /** Units leave stock for the demo, on one of its days. {@code own}: attending staff acting on their own demo. */
    public ClientDemoDTOs.Detail handOut(Long id, ClientDemoDTOs.UnitsRequest request, boolean own) {
        return move(id, request, own, DemoStockClient.Direction.OUT);
    }

    /** Units come back into stock from the demo. {@code own}: attending staff acting on their own demo. */
    public ClientDemoDTOs.Detail handBack(Long id, ClientDemoDTOs.UnitsRequest request, boolean own) {
        return move(id, request, own, DemoStockClient.Direction.BACK);
    }

    private ClientDemoDTOs.Detail move(Long id, ClientDemoDTOs.UnitsRequest request, boolean own, DemoStockClient.Direction direction) {
        User actor = currentUserProvider.getCurrentUser();
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        List<String> codes = BarcodeCodes.normalizeAll(request.getBarcodes(), "barcodes", errors);
        ApiErrors.throwIfAny(errors);

        // Checked first, so the storefront is only asked for a move that can be recorded here
        record Checked(String reference, Map<Long, Integer> quantities) {
        }
        Checked checked = Objects.requireNonNull(transactions.execute(status -> {
            status.setRollbackOnly();
            ClientDemo demo = demoService.find(id);
            List<DemoUnit> units = check(demo, codes, actor, own, direction);
            return new Checked(demo.reference(), quantities(units));
        }));

        String key = checked.reference() + "-" + direction.name() + "-" + UUID.randomUUID();
        stockClient.move(key, direction, checked.reference(), checked.quantities(), actor.getUsername());
        try {
            return transactions.execute(status -> {
                ClientDemo demo = demoService.lock(id);
                List<DemoUnit> units = check(demo, codes, actor, own, direction);
                record(demo, units, key, actor, direction);
                return views.detail(demo, actor);
            });
        } catch (RuntimeException exception) {
            stockClient.reverse(key);
            throw exception;
        }
    }

    // The demo's units carrying the codes, after checking the caller, the day and that each can move this way
    private List<DemoUnit> check(ClientDemo demo, List<String> codes, User actor, boolean own, DemoStockClient.Direction direction) {
        if (own) {
            demoService.attended(demo.getId(), actor);
        }
        if (demo.getStatus() != DemoStatus.APPROVED) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_WRONG_STATUS, demo.reference() + " is " + demo.getStatus().label().toLowerCase(Locale.ROOT)
                    + ". Units are handed out and back while an approved demo runs.");
        }
        boolean out = direction == DemoStockClient.Direction.OUT;
        LocalDate today = demoService.today();
        if (out && (today.isBefore(demo.getStartDate()) || today.isAfter(demo.endDate()))) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_WRONG_STATUS, "Units leave stock on the demo's days ("
                    + DAY.format(demo.getStartDate()) + (demo.getNumberOfDays() > 1 ? " to " + DAY.format(demo.endDate()) : "")
                    + "), not today.");
        }
        Map<String, DemoUnit> byCode = demo.getUnits().stream().collect(Collectors.toMap(DemoUnit::getCode, Function.identity()));
        DemoUnitStatus from = out ? DemoUnitStatus.RESERVED : DemoUnitStatus.OUT;
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        List<DemoUnit> units = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            DemoUnit unit = byCode.get(codes.get(i));
            String field = "barcodes[" + i + "]";
            if (unit == null) {
                errors.add(ApiErrors.error(field, "Unit " + codes.get(i) + " is not picked for " + demo.reference() + "."));
            } else if (unit.getStatus() != from) {
                errors.add(ApiErrors.error(field, "Unit " + codes.get(i) + " is " + unit.getStatus().label().toLowerCase(Locale.ROOT)
                        + (out ? ", so it can't be handed out." : ", so it can't be handed back.")));
            } else {
                units.add(unit);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.DEMO_UNIT_NOT_AVAILABLE, HttpStatus.CONFLICT, errors);
        }
        return units;
    }

    private void record(ClientDemo demo, List<DemoUnit> units, String key, User actor, DemoStockClient.Direction direction) {
        boolean out = direction == DemoStockClient.Direction.OUT;
        List<Barcode> barcodes = registry.lockLive(units.stream().map(DemoUnit::getCode).toList(), "barcodes");
        BarcodeStatus expected = out ? BarcodeStatus.IN_STOCK : BarcodeStatus.ON_DEMO;
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        for (Barcode barcode : barcodes) {
            if (barcode.getStatus() != expected) {
                errors.add(ApiErrors.error("barcodes", "Unit " + barcode.getCode() + " is " + BarcodeRegistry.describe(barcode.getStatus())
                        + (out ? ", so it can't leave stock for the demo." : ", not out on a demo.")));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.DEMO_UNIT_NOT_AVAILABLE, HttpStatus.CONFLICT, errors);
        }
        BarcodeRegistry.Actor who = BarcodeRegistry.Actor.of(actor);
        Instant now = demoService.now();
        if (out) {
            registry.handOutForDemo(barcodes, who, "Out on " + demo.reference());
            units.forEach(unit -> {
                unit.setStatus(DemoUnitStatus.OUT);
                unit.setHandedOutAt(now);
                unit.setHandedOutBy(actor.getId());
                unit.setOutMoveKey(key);
            });
        } else {
            registry.returnFromDemo(barcodes, who, "Back from " + demo.reference());
            units.forEach(unit -> {
                unit.setStatus(DemoUnitStatus.RETURNED);
                unit.setReturnedAt(now);
                unit.setReturnedBy(actor.getId());
                unit.setBackMoveKey(key);
            });
        }
        demo.setUpdatedAt(now);
        demoService.event(demo, out ? DemoEventType.UNITS_HANDED_OUT : DemoEventType.UNITS_HANDED_BACK, actor,
                describeUnits(demo, units), null);
    }

    /**
     * A Super Admin writes off a unit that did not come back (lost, damaged): it leaves stock for good with its
     * pieces. The storefront's stock already went down when it was handed out.
     */
    @Transactional
    public ClientDemoDTOs.Detail writeOff(Long id, ClientDemoDTOs.WriteOffRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = demoService.lock(id);
        String code = BarcodeCodes.normalize(request.getBarcode());
        DemoUnit unit = demo.getUnits().stream().filter(candidate -> candidate.getCode().equals(code)).findFirst()
                .orElseThrow(() -> ApiErrors.fieldError("barcode", "Unit " + code + " is not picked for " + demo.reference() + "."));
        if (unit.getStatus() != DemoUnitStatus.OUT) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_UNIT_NOT_AVAILABLE, "Unit " + code + " is "
                    + unit.getStatus().label().toLowerCase(Locale.ROOT) + ". Only a unit out on the demo can be written off.");
        }
        String reason = request.getReason().trim();
        Barcode barcode = registry.lockLive(List.of(code), "barcode").getFirst();
        registry.writeOffFromDemo(barcode, BarcodeRegistry.Actor.of(actor), "Written off from " + demo.reference() + ": " + reason);
        unit.setStatus(DemoUnitStatus.WRITTEN_OFF);
        unit.setWriteOffReason(reason);
        unit.setReturnedAt(demoService.now());
        unit.setReturnedBy(actor.getId());
        demo.setUpdatedAt(demoService.now());
        demoService.event(demo, DemoEventType.UNIT_WRITTEN_OFF, actor, describeUnits(demo, List.of(unit)), reason);
        return views.detail(demo, actor);
    }

    // ------------------------------------------------------------------ helpers

    // Product id → units, in id order
    private static Map<Long, Integer> quantities(List<DemoUnit> units) {
        Map<Long, Integer> quantities = new TreeMap<>();
        units.forEach(unit -> quantities.merge(unit.getProductId(), 1, Integer::sum));
        return quantities;
    }

    // "Product A: CODE1, CODE2; Product B: CODE3"
    private static String describeUnits(ClientDemo demo, List<DemoUnit> units) {
        Map<Long, String> names = demo.getProducts().stream().collect(Collectors.toMap(DemoProduct::getProductId, DemoProduct::getProductName));
        Map<Long, List<String>> byProduct = new LinkedHashMap<>();
        units.forEach(unit -> byProduct.computeIfAbsent(unit.getProductId(), product -> new ArrayList<>()).add(unit.getCode()));
        return byProduct.entrySet().stream()
                .map(entry -> names.getOrDefault(entry.getKey(), "Product " + entry.getKey()) + ": " + String.join(", ", entry.getValue()))
                .collect(Collectors.joining("; "));
    }
}
