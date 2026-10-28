package com.tinqa.procurement.stock.service.impl;

import com.tinqa.procurement.barcode.service.BarcodeRegistry;
import com.tinqa.procurement.barcode.service.ProductUnitMovements;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;
import com.tinqa.procurement.stock.entity.ItemStockMovement;
import com.tinqa.procurement.stock.entity.ItemStockMovementAllocation;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.enums.ItemStockMovementStatus;
import com.tinqa.procurement.stock.repository.ItemStockMovementAllocationRepository;
import com.tinqa.procurement.stock.repository.ItemStockMovementRepository;
import com.tinqa.procurement.stock.repository.StockRepository;
import com.tinqa.procurement.stock.service.ItemStockMovementService;
import com.tinqa.procurement.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemStockMovementServiceImpl implements ItemStockMovementService {

    // Source and reference of a placeholder recorded when an unknown movement is reversed
    private static final String UNKNOWN = "UNKNOWN";

    private final ItemStockMovementRepository movementRepository;
    private final ItemStockMovementAllocationRepository allocationRepository;
    private final StockRepository stockRepository;
    private final ItemRepository itemRepository;
    private final StockService stockService;
    private final ProductUnitMovements productUnits;
    private final BarcodeRegistry barcodeRegistry;
    private final Clock clock;

    @Override
    @Transactional
    public ItemStockMovementDTOs.MovementResponse applyMovement(ItemStockMovementDTOs.MovementRequest request) {
        Optional<ItemStockMovement> existing = movementRepository.findByMovementId(request.getMovementId());
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        List<ItemStockMovementDTOs.Line> lines = Optional.ofNullable(request.getLines()).orElse(List.of());
        validateLines(lines, request.getProductUnits() != null);
        Map<Long, Item> items = loadItems(lines);
        movementRepository.lockReference(request.getSource(), request.getReference());

        ItemStockMovement movement = claim(ItemStockMovement.builder()
                .movementId(request.getMovementId())
                .source(request.getSource())
                .reference(request.getReference())
                .status(ItemStockMovementStatus.APPLIED)
                .performedBy(request.getPerformedBy())
                .build());
        // Product units built or taken out, with the pieces inside them: checked and locked before anything moves
        ProductUnitMovements.Plan units = request.getProductUnits() == null ? null
                : productUnits.prepare(request.getProductUnits(), request.getReference(), lines);

        // Check every line (locking the rows involved) before moving anything, so failures report all problems
        List<ApiResponse.ApiErrorItem> shortages = new ArrayList<>();
        List<ApiResponse.ApiErrorItem> unnamed = new ArrayList<>();
        Map<Integer, List<ItemStockMovementAllocationRepository.StockNetUnits>> returnPlans = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ItemStockMovementDTOs.Line line = lines.get(i);
            Item item = items.get(line.getItemId());
            String field = "lines[" + i + "].quantity";
            if (line.getQuantity().signum() > 0) {
                BigDecimal available = stockService.lockConsumableUnits(item.getId());
                // Pieces with a barcode are taken by naming them; the rest must come from pieces that have none
                BigDecimal rest = line.getQuantity().subtract(named(units == null ? Map.of() : units.fittedByStock(item.getId())));
                if (available.compareTo(line.getQuantity()) < 0) {
                    shortages.add(ApiErrors.error(field, "Not enough stock for " + item.getName() + ": need "
                            + Quantities.plain(line.getQuantity()) + ", available " + Quantities.plain(available)));
                } else if (rest.signum() > 0 && stockService.lockUntrackedConsumableUnits(item.getId()).compareTo(rest) < 0) {
                    unnamed.add(ApiErrors.error(field, "Scan the barcode of every " + item.getName() + " piece used: "
                            + Quantities.plain(rest) + " more needed"));
                }
            } else {
                // Returns are capped at what the reference still holds (e.g. after a rejected product released
                // its stock), so they can never create stock out of nothing
                returnPlans.put(i, new ArrayList<>(allocationRepository.findHeldUnitsByStock(
                        request.getSource(), request.getReference(), item.getId(), ItemStockMovementStatus.APPLIED)));
            }
        }
        if (!shortages.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(shortages), ErrorCodes.INSUFFICIENT_ITEM_STOCK, HttpStatus.CONFLICT, shortages);
        }
        if (!unnamed.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(unnamed), ErrorCodes.BARCODES_REQUIRED, HttpStatus.BAD_REQUEST, unnamed);
        }

        for (int i = 0; i < lines.size(); i++) {
            ItemStockMovementDTOs.Line line = lines.get(i);
            Item item = items.get(line.getItemId());
            if (line.getQuantity().signum() > 0) {
                Map<Long, BigDecimal> scanned = units == null ? Map.of() : units.fittedByStock(item.getId());
                Map<Stock, BigDecimal> consumed = new LinkedHashMap<>(stockService.consumeUnits(scanned, null));
                BigDecimal rest = line.getQuantity().subtract(named(scanned));
                if (rest.signum() > 0) {
                    stockService.consumeItemStock(item, rest, null).forEach((stock, taken) -> consumed.merge(stock, taken, BigDecimal::add));
                }
                for (Map.Entry<Stock, BigDecimal> entry : consumed.entrySet()) {
                    addAllocation(movement, i, item.getId(), entry.getKey(), entry.getValue());
                }
            } else {
                Map<Long, BigDecimal> returned = new LinkedHashMap<>(returnNamed(returnPlans.get(i),
                        units == null ? Map.of() : units.returnedByStock(item.getId()), request.getReference()));
                BigDecimal rest = line.getQuantity().negate().subtract(named(returned));
                if (rest.signum() > 0) {
                    planReturn(returnPlans.get(i), rest).forEach((stockId, back) -> returned.merge(stockId, back, BigDecimal::add));
                }
                stockService.restoreStock(returned, null);
                for (Map.Entry<Long, BigDecimal> entry : returned.entrySet()) {
                    addAllocation(movement, i, item.getId(), stockRepository.getReferenceById(entry.getKey()), entry.getValue().negate());
                }
            }
        }

        if (units != null) {
            productUnits.apply(units, request.getMovementId(), request.getPerformedBy());
        }
        return toResponse(movementRepository.save(movement));
    }

    private static BigDecimal named(Map<Long, BigDecimal> unitsByStock) {
        return unitsByStock.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Named pieces go back to the stock entries they came from. Takes them off {@code held} (what the reference holds
     * per entry), so pieces returned without a name are planned from the rest.
     */
    private static Map<Long, BigDecimal> returnNamed(List<ItemStockMovementAllocationRepository.StockNetUnits> held,
                                                     Map<Long, BigDecimal> named, String reference) {
        if (named.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> heldByStock = new HashMap<>();
        held.forEach(stock -> heldByStock.put(stock.getStockId(), stock.getNetUnits()));
        named.forEach((stockId, units) -> {
            if (heldByStock.getOrDefault(stockId, BigDecimal.ZERO).compareTo(units) < 0) {
                throw new ApiException("Pieces returned from " + reference + " were not taken from stock entry " + stockId
                        + " by it", ErrorCodes.INVALID_MOVEMENT_LINES, HttpStatus.BAD_REQUEST);
            }
        });
        held.replaceAll(stock -> {
            BigDecimal left = stock.getNetUnits().subtract(named.getOrDefault(stock.getStockId(), BigDecimal.ZERO));
            return new HeldUnits(stock.getStockId(), left, stock.getLastAllocationId());
        });
        held.removeIf(stock -> stock.getNetUnits().signum() <= 0);
        return named;
    }

    private record HeldUnits(Long stockId, BigDecimal netUnits, Long lastAllocationId)
            implements ItemStockMovementAllocationRepository.StockNetUnits {
        @Override
        public Long getStockId() {
            return stockId;
        }

        @Override
        public BigDecimal getNetUnits() {
            return netUnits;
        }

        @Override
        public Long getLastAllocationId() {
            return lastAllocationId;
        }
    }

    @Override
    @Transactional
    public ItemStockMovementDTOs.MovementResponse reverseMovement(UUID movementId, ItemStockMovementDTOs.ReverseRequest request) {
        ItemStockMovementDTOs.ReverseRequest details = request == null ? new ItemStockMovementDTOs.ReverseRequest() : request;
        Optional<ItemStockMovement> found = movementRepository.findByMovementIdForUpdate(movementId);
        if (found.isEmpty()) {
            return toResponse(claim(ItemStockMovement.builder()
                    .movementId(movementId)
                    .source(details.getSource() == null || details.getSource().isBlank() ? UNKNOWN : details.getSource())
                    .reference(details.getReference() == null || details.getReference().isBlank() ? UNKNOWN : details.getReference())
                    .status(ItemStockMovementStatus.REVERSED)
                    .performedBy(details.getPerformedBy())
                    .reversedAt(LocalDateTime.now(clock))
                    .reversedBy(details.getPerformedBy())
                    .build()));
        }

        ItemStockMovement movement = found.get();
        movementRepository.lockReference(movement.getSource(), movement.getReference());
        if (movement.getStatus() == ItemStockMovementStatus.REVERSED) {
            return toResponse(movement);
        }

        Map<Long, BigDecimal> consumedByStock = new LinkedHashMap<>();
        Map<Long, BigDecimal> returnedByStock = new LinkedHashMap<>();
        Map<Long, Map<Long, BigDecimal>> consumedByItemAndStock = new LinkedHashMap<>();
        for (ItemStockMovementAllocation allocation : movement.getAllocations()) {
            Long stockId = allocation.getStock().getId();
            if (allocation.getUnits().signum() > 0) {
                consumedByStock.merge(stockId, allocation.getUnits(), BigDecimal::add);
                consumedByItemAndStock.computeIfAbsent(allocation.getItemId(), id -> new HashMap<>())
                        .merge(stockId, allocation.getUnits(), BigDecimal::add);
            } else {
                returnedByStock.merge(stockId, allocation.getUnits().negate(), BigDecimal::add);
            }
        }

        // Putting consumed units back must not leave the reference holding less than nothing on any batch
        for (Map.Entry<Long, Map<Long, BigDecimal>> itemEntry : consumedByItemAndStock.entrySet()) {
            Map<Long, BigDecimal> heldByStock = allocationRepository.findHeldUnitsByStock(
                            movement.getSource(), movement.getReference(), itemEntry.getKey(), ItemStockMovementStatus.APPLIED)
                    .stream()
                    .collect(Collectors.toMap(ItemStockMovementAllocationRepository.StockNetUnits::getStockId,
                            ItemStockMovementAllocationRepository.StockNetUnits::getNetUnits));
            for (Map.Entry<Long, BigDecimal> stockEntry : itemEntry.getValue().entrySet()) {
                BigDecimal held = heldByStock.getOrDefault(stockEntry.getKey(), BigDecimal.ZERO);
                if (held.compareTo(stockEntry.getValue()) < 0) {
                    throw new ApiException("Movement " + movementId + " cannot be reversed: units it consumed were already "
                            + "returned by a later movement for " + movement.getReference() + ". Reverse that movement first.",
                            ErrorCodes.REVERSAL_NOT_POSSIBLE, HttpStatus.CONFLICT);
                }
            }
        }

        // Barcodes it changed must be as it left them; it undoes them too
        barcodeRegistry.requireReversible(movementId);
        stockService.takeBackStock(returnedByStock, null);
        stockService.restoreStock(consumedByStock, null);
        barcodeRegistry.reverse(movementId, BarcodeRegistry.Actor.service(details.getPerformedBy()));

        movement.setStatus(ItemStockMovementStatus.REVERSED);
        movement.setReversedAt(LocalDateTime.now(clock));
        movement.setReversedBy(details.getPerformedBy());
        return toResponse(movementRepository.save(movement));
    }

    @Override
    @Transactional(readOnly = true)
    public ItemStockMovementDTOs.MovementResponse getMovement(UUID movementId) {
        return movementRepository.findByMovementId(movementId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Item stock movement not found: " + movementId));
    }

    @Override
    @Transactional
    public ItemStockMovementDTOs.MovementResponse releaseReference(String source, String reference, String performedBy) {
        return reconcileReference(source, reference, Map.of(), performedBy);
    }

    @Override
    @Transactional(readOnly = true)
    public ItemStockMovementDTOs.ProductHoldings productHoldings(String source, Long productId) {
        Map<Long, BigDecimal> pieces = new LinkedHashMap<>();
        allocationRepository.findHeldUnitsByItem(source, productReference(productId), ItemStockMovementStatus.APPLIED)
                .forEach(held -> pieces.put(held.getItemId(), held.getNetUnits()));
        return ItemStockMovementDTOs.ProductHoldings.builder()
                .heldUnitCodes(productUnits.readHeldUnitCodes(productId))
                .heldPiecesByItem(pieces)
                .build();
    }

    @Override
    @Transactional
    public ItemStockMovementDTOs.MovementResponse releaseProduct(String source, Long productId, String performedBy) {
        String reference = productReference(productId);
        movementRepository.lockReference(source, reference);
        // Every unit still held is withdrawn, and the pieces inside go back to the stock entries they came from
        List<String> held = productUnits.heldUnitCodes(productId);
        ItemStockMovementDTOs.ProductUnitChange units = ItemStockMovementDTOs.ProductUnitChange.builder()
                .productId(productId)
                .stockBefore(held.size())
                .stockAfter(0)
                .removed(held)
                .returnParts(true)
                .withdrawRegistration(true)
                .build();
        return reconcileReference(source, reference, Map.of(), performedBy, held.isEmpty() ? null : units);
    }

    @Override
    @Transactional
    public ItemStockMovementDTOs.MovementResponse reserveProduct(String source, Long productId, String productTitle,
                                                                 Map<Long, BigDecimal> targetUnitsByItem,
                                                                 List<ItemStockMovementDTOs.NewUnit> newUnits, String performedBy) {
        String reference = productReference(productId);
        movementRepository.lockReference(source, reference);
        int held = productUnits.heldUnitCodes(productId).size();
        ItemStockMovementDTOs.ProductUnitChange units = ItemStockMovementDTOs.ProductUnitChange.builder()
                .productId(productId)
                .productTitle(productTitle)
                .stockBefore(held)
                .stockAfter(held + newUnits.size())
                .awaitingApproval(true)
                .added(newUnits)
                .build();
        return reconcileReference(source, reference, targetUnitsByItem, performedBy, newUnits.isEmpty() ? null : units);
    }

    @Override
    @Transactional
    public ItemStockMovementDTOs.MovementResponse reconcileReference(String source, String reference,
                                                                     Map<Long, BigDecimal> targetUnitsByItem, String performedBy) {
        return reconcileReference(source, reference, targetUnitsByItem, performedBy, null);
    }

    private ItemStockMovementDTOs.MovementResponse reconcileReference(String source, String reference,
                                                                      Map<Long, BigDecimal> targetUnitsByItem, String performedBy,
                                                                      ItemStockMovementDTOs.ProductUnitChange units) {
        movementRepository.lockReference(source, reference);
        Map<Long, BigDecimal> held = allocationRepository.findHeldUnitsByItem(source, reference, ItemStockMovementStatus.APPLIED)
                .stream()
                .collect(Collectors.toMap(ItemStockMovementAllocationRepository.ItemNetUnits::getItemId,
                        ItemStockMovementAllocationRepository.ItemNetUnits::getNetUnits));

        Set<Long> itemIds = new TreeSet<>(held.keySet());
        itemIds.addAll(targetUnitsByItem.keySet());
        List<ItemStockMovementDTOs.Line> lines = new ArrayList<>();
        for (Long itemId : itemIds) {
            BigDecimal delta = targetUnitsByItem.getOrDefault(itemId, BigDecimal.ZERO).max(BigDecimal.ZERO)
                    .subtract(held.getOrDefault(itemId, BigDecimal.ZERO));
            if (delta.signum() != 0) {
                lines.add(ItemStockMovementDTOs.Line.builder().itemId(itemId).quantity(delta).build());
            }
        }
        if (lines.isEmpty() && units == null) {
            return null;
        }
        return applyMovement(ItemStockMovementDTOs.MovementRequest.builder()
                .movementId(UUID.randomUUID())
                .source(source)
                .reference(reference)
                .lines(lines)
                .performedBy(performedBy)
                .productUnits(units)
                .build());
    }

    private static String productReference(Long productId) {
        return "product:" + productId;
    }

    private ItemStockMovementDTOs.MovementResponse replay(ItemStockMovement existing, ItemStockMovementDTOs.MovementRequest request) {
        boolean placeholder = UNKNOWN.equals(existing.getSource()) && UNKNOWN.equals(existing.getReference());
        if (!placeholder && (!existing.getSource().equals(request.getSource())
                || !existing.getReference().equals(request.getReference()))) {
            throw new ApiException("Movement " + request.getMovementId() + " was already used for "
                    + existing.getSource() + " " + existing.getReference(), ErrorCodes.MOVEMENT_ID_CONFLICT, HttpStatus.CONFLICT);
        }
        return toResponse(existing);
    }

    // Inserting the row first makes a concurrent request with the same movementId wait, then fail here
    private ItemStockMovement claim(ItemStockMovement movement) {
        try {
            return movementRepository.saveAndFlush(movement);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException("Movement " + movement.getMovementId() + " is already being processed. Retry to get its result.",
                    ErrorCodes.MOVEMENT_IN_PROGRESS, HttpStatus.CONFLICT);
        }
    }

    private void validateLines(List<ItemStockMovementDTOs.Line> lines, boolean movesProductUnits) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        if (lines.isEmpty() && !movesProductUnits) {
            errors.add(ApiErrors.error("lines", "At least one line is required"));
        }
        Set<Long> itemIds = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            ItemStockMovementDTOs.Line line = lines.get(i);
            if (line.getQuantity().signum() == 0) {
                errors.add(ApiErrors.error("lines[" + i + "].quantity", "Quantity cannot be zero"));
            }
            if (!itemIds.add(line.getItemId())) {
                errors.add(ApiErrors.error("lines[" + i + "].itemId", "Item " + line.getItemId() + " is listed more than once; combine its quantities"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.INVALID_MOVEMENT_LINES, HttpStatus.BAD_REQUEST, errors);
        }
    }

    private Map<Long, Item> loadItems(List<ItemStockMovementDTOs.Line> lines) {
        Map<Long, Item> items = itemRepository.findAllById(lines.stream().map(ItemStockMovementDTOs.Line::getItemId).toList())
                .stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        List<ApiResponse.ApiErrorItem> missing = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!items.containsKey(lines.get(i).getItemId())) {
                missing.add(ApiErrors.error("lines[" + i + "].itemId", "Item not found with ID: " + lines.get(i).getItemId()));
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(missing), ErrorCodes.ITEM_NOT_FOUND, HttpStatus.BAD_REQUEST, missing);
        }
        return items;
    }

    // Most recently consumed batches first
    private Map<Long, BigDecimal> planReturn(List<ItemStockMovementAllocationRepository.StockNetUnits> held, BigDecimal quantity) {
        Map<Long, BigDecimal> plan = new LinkedHashMap<>();
        BigDecimal remaining = quantity;
        for (ItemStockMovementAllocationRepository.StockNetUnits stock : held) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal units = stock.getNetUnits().min(remaining);
            plan.put(stock.getStockId(), units);
            remaining = remaining.subtract(units);
        }
        return plan;
    }

    private void addAllocation(ItemStockMovement movement, int lineIndex, Long itemId, Stock stock, BigDecimal units) {
        movement.getAllocations().add(ItemStockMovementAllocation.builder()
                .movement(movement)
                .lineIndex(lineIndex)
                .itemId(itemId)
                .stock(stock)
                .units(units)
                .build());
    }

    private ItemStockMovementDTOs.MovementResponse toResponse(ItemStockMovement movement) {
        Map<Integer, List<ItemStockMovementAllocation>> byLine = movement.getAllocations().stream()
                .collect(Collectors.groupingBy(ItemStockMovementAllocation::getLineIndex, TreeMap::new, Collectors.toList()));

        List<ItemStockMovementDTOs.LineResult> lines = byLine.values().stream()
                .map(allocations -> ItemStockMovementDTOs.LineResult.builder()
                        .itemId(allocations.getFirst().getItemId())
                        .quantity(allocations.stream().map(ItemStockMovementAllocation::getUnits).reduce(BigDecimal.ZERO, BigDecimal::add))
                        .allocations(allocations.stream()
                                .map(allocation -> ItemStockMovementDTOs.Allocation.builder()
                                        .stockId(allocation.getStock().getId())
                                        .batchNumber(allocation.getStock().getBatchNumber())
                                        .units(allocation.getUnits())
                                        .build())
                                .toList())
                        .build())
                .toList();

        return ItemStockMovementDTOs.MovementResponse.builder()
                .movementId(movement.getMovementId())
                .source(movement.getSource())
                .reference(movement.getReference())
                .status(movement.getStatus())
                .performedBy(movement.getPerformedBy())
                .createdAt(movement.getCreatedAt())
                .reversedAt(movement.getReversedAt())
                .reversedBy(movement.getReversedBy())
                .lines(lines)
                .build();
    }
}
