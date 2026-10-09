package com.tinqa.procurement.stock.service.impl;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemStockMovementServiceImpl implements ItemStockMovementService {

    static final String INSUFFICIENT_ITEM_STOCK = "INSUFFICIENT_ITEM_STOCK";
    static final String RETURN_EXCEEDS_CONSUMED = "RETURN_EXCEEDS_CONSUMED";

    private static final String UNKNOWN = "UNKNOWN";

    private final ItemStockMovementRepository movementRepository;
    private final ItemStockMovementAllocationRepository allocationRepository;
    private final StockRepository stockRepository;
    private final ItemRepository itemRepository;
    private final StockService stockService;

    @Override
    @Transactional
    public ItemStockMovementDTOs.MovementResponse applyMovement(ItemStockMovementDTOs.MovementRequest request) {
        Optional<ItemStockMovement> existing = movementRepository.findByMovementId(request.getMovementId());
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        List<ItemStockMovementDTOs.Line> lines = request.getLines();
        validateLines(lines);
        Map<Long, Item> items = loadItems(lines);

        ItemStockMovement movement = claim(ItemStockMovement.builder()
                .movementId(request.getMovementId())
                .source(request.getSource())
                .reference(request.getReference())
                .status(ItemStockMovementStatus.APPLIED)
                .performedBy(request.getPerformedBy())
                .build());

        // Check every line (locking the rows involved) before moving anything, so failures report all problems
        List<ApiResponse.ApiErrorItem> shortages = new ArrayList<>();
        List<ApiResponse.ApiErrorItem> overReturns = new ArrayList<>();
        Map<Integer, List<ItemStockMovementAllocationRepository.StockNetUnits>> returnPlans = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ItemStockMovementDTOs.Line line = lines.get(i);
            Item item = items.get(line.getItemId());
            String field = "lines[" + i + "].quantity";
            if (line.getQuantity().signum() > 0) {
                BigDecimal available = stockService.lockConsumableUnits(item.getId());
                if (available.compareTo(line.getQuantity()) < 0) {
                    shortages.add(error(field, "Not enough stock for " + item.getName() + ": need "
                            + plain(line.getQuantity()) + ", available " + plain(available)));
                }
            } else {
                List<ItemStockMovementAllocationRepository.StockNetUnits> held = allocationRepository.findHeldUnitsByStock(
                        request.getSource(), request.getReference(), item.getId(), ItemStockMovementStatus.APPLIED);
                BigDecimal totalHeld = held.stream()
                        .map(ItemStockMovementAllocationRepository.StockNetUnits::getNetUnits)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal toReturn = line.getQuantity().negate();
                if (totalHeld.compareTo(toReturn) < 0) {
                    overReturns.add(error(field, "Cannot return " + plain(toReturn) + " of " + item.getName()
                            + " for " + request.getReference() + ": only " + plain(totalHeld) + " were consumed by it"));
                }
                returnPlans.put(i, held);
            }
        }
        if (!shortages.isEmpty()) {
            throw new ApiException(joinMessages(shortages), INSUFFICIENT_ITEM_STOCK, HttpStatus.CONFLICT, shortages);
        }
        if (!overReturns.isEmpty()) {
            throw new ApiException(joinMessages(overReturns), RETURN_EXCEEDS_CONSUMED, HttpStatus.BAD_REQUEST, overReturns);
        }

        for (int i = 0; i < lines.size(); i++) {
            ItemStockMovementDTOs.Line line = lines.get(i);
            Item item = items.get(line.getItemId());
            if (line.getQuantity().signum() > 0) {
                Map<Stock, BigDecimal> consumed = stockService.consumeItemStock(item, line.getQuantity(), null);
                for (Map.Entry<Stock, BigDecimal> entry : consumed.entrySet()) {
                    addAllocation(movement, i, item.getId(), entry.getKey(), entry.getValue());
                }
            } else {
                Map<Long, BigDecimal> returned = planReturn(returnPlans.get(i), line.getQuantity().negate());
                stockService.restoreStock(returned, null);
                for (Map.Entry<Long, BigDecimal> entry : returned.entrySet()) {
                    addAllocation(movement, i, item.getId(), stockRepository.getReferenceById(entry.getKey()), entry.getValue().negate());
                }
            }
        }

        return toResponse(movementRepository.save(movement));
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
                    .reversedAt(LocalDateTime.now())
                    .reversedBy(details.getPerformedBy())
                    .build()));
        }

        ItemStockMovement movement = found.get();
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
                            "REVERSAL_NOT_POSSIBLE", HttpStatus.CONFLICT);
                }
            }
        }

        stockService.takeBackStock(returnedByStock, null);
        stockService.restoreStock(consumedByStock, null);

        movement.setStatus(ItemStockMovementStatus.REVERSED);
        movement.setReversedAt(LocalDateTime.now());
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

    private ItemStockMovementDTOs.MovementResponse replay(ItemStockMovement existing, ItemStockMovementDTOs.MovementRequest request) {
        boolean placeholder = UNKNOWN.equals(existing.getSource()) && UNKNOWN.equals(existing.getReference());
        if (!placeholder && (!existing.getSource().equals(request.getSource())
                || !existing.getReference().equals(request.getReference()))) {
            throw new ApiException("Movement " + request.getMovementId() + " was already used for "
                    + existing.getSource() + " " + existing.getReference(), "MOVEMENT_ID_CONFLICT", HttpStatus.CONFLICT);
        }
        return toResponse(existing);
    }

    // Inserting the row first makes a concurrent request with the same movementId wait, then fail here
    private ItemStockMovement claim(ItemStockMovement movement) {
        try {
            return movementRepository.saveAndFlush(movement);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException("Movement " + movement.getMovementId() + " is already being processed. Retry to get its result.",
                    "MOVEMENT_IN_PROGRESS", HttpStatus.CONFLICT);
        }
    }

    private void validateLines(List<ItemStockMovementDTOs.Line> lines) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        Set<Long> itemIds = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            ItemStockMovementDTOs.Line line = lines.get(i);
            if (line.getQuantity().signum() == 0) {
                errors.add(error("lines[" + i + "].quantity", "Quantity cannot be zero"));
            }
            if (!itemIds.add(line.getItemId())) {
                errors.add(error("lines[" + i + "].itemId", "Item " + line.getItemId() + " is listed more than once; combine its quantities"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(joinMessages(errors), "INVALID_MOVEMENT_LINES", HttpStatus.BAD_REQUEST, errors);
        }
    }

    private Map<Long, Item> loadItems(List<ItemStockMovementDTOs.Line> lines) {
        Map<Long, Item> items = itemRepository.findAllById(lines.stream().map(ItemStockMovementDTOs.Line::getItemId).toList())
                .stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        List<ApiResponse.ApiErrorItem> missing = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!items.containsKey(lines.get(i).getItemId())) {
                missing.add(error("lines[" + i + "].itemId", "Item not found with ID: " + lines.get(i).getItemId()));
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(joinMessages(missing), "ITEM_NOT_FOUND", HttpStatus.BAD_REQUEST, missing);
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

    private ApiResponse.ApiErrorItem error(String field, String message) {
        return new ApiResponse.ApiErrorItem(field, message);
    }

    private String joinMessages(List<ApiResponse.ApiErrorItem> errors) {
        return errors.stream().map(ApiResponse.ApiErrorItem::getMessage).collect(Collectors.joining("; "));
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
