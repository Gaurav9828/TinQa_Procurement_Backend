package com.tinqa.procurement.stock.service.impl;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.common.util.ReferenceNumberGenerator;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.stock.dto.StockDTOs;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.exception.InsufficientStockException;
import com.tinqa.procurement.stock.mapper.StockMapper;
import com.tinqa.procurement.stock.notification.StockNotifier;
import com.tinqa.procurement.stock.repository.StockRepository;
import com.tinqa.procurement.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    // Only approved stock from delivered orders can be consumed
    private static final ApprovalStatus CONSUMABLE_APPROVAL = ApprovalStatus.APPROVED;
    private static final OrderStatus CONSUMABLE_ORDER_STATUS = OrderStatus.DELIVERED;
    private static final long NO_STOCK_ENTRY = -1L;

    private final StockRepository stockRepository;
    private final OrderRepository orderRepository;
    private final ItemRepository itemRepository;
    private final CurrentUserProvider currentUserProvider;
    private final StockNotifier stockNotifier;
    private final StockMapper stockMapper;
    private final ReferenceNumberGenerator referenceNumbers;
    private final Clock clock;

    @Override
    @Transactional
    public StockDTOs.Response createStockFromOrder(StockDTOs.CreateFromOrderRequest request) {
        User submitter = currentUserProvider.getCurrentUser();
        Order order = orderRepository.findByOrderNumberForUpdate(request.getOrderNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with number: " + request.getOrderNumber()));

        // Only delivered goods are received into stock. Checked under the order lock, so a concurrent status change
        // (e.g. a return request, which is refused once stock exists) cannot slip in between.
        if (order.getOrderStatus() != OrderStatus.DELIVERED) {
            throw orderNumberConflict(ErrorCodes.ORDER_NOT_DELIVERED, "Order " + order.getOrderNumber() + " is "
                            + order.getOrderStatus().label() + ". Stock can only be created from a delivered order.",
                    "Order " + order.getOrderNumber() + " has not been delivered");
        }
        // One order, one stock entry: the order's quantity is received once (checked under the order lock)
        stockRepository.findFirstByOrderOrderNumber(order.getOrderNumber()).ifPresent(existing -> {
            throw orderNumberConflict(ErrorCodes.ORDER_ALREADY_HAS_STOCK, "Order " + order.getOrderNumber()
                            + " already has stock entry " + existing.getStockIdentityNumber()
                            + ". An order can be used for only one stock entry; update that entry instead of creating a new one.",
                    "Order " + order.getOrderNumber() + " already has a stock entry");
        });

        ensureArrivalNotBeforeOrder(order, request.getDateOfArrival());
        ensureWithinOrderQuantity(order, null, request.getUnitsPassedTest().add(request.getDefectedUnits()), "unitsPassedTest");

        Stock stock = Stock.builder()
                .stockIdentityNumber(referenceNumbers.stockIdentityNumber())
                .batchNumber(referenceNumbers.batchNumber(order.getId()))
                .order(order)
                .dealer(order.getDealer())
                .item(order.getItem())
                .unitsPassedTest(request.getUnitsPassedTest())
                .defectedUnits(request.getDefectedUnits())
                .availableUnits(request.getUnitsPassedTest())
                .hasTested(request.getHasTested())
                // Stored as {} when not sent (the column is NOT NULL)
                .additionalInfo(request.getAdditionalInfo() != null ? request.getAdditionalInfo() : new LinkedHashMap<>())
                .approvalStatus(ApprovalStatus.PENDING)
                .dateOfArrival(request.getDateOfArrival())
                .createdBy(submitter.getId())
                .updatedBy(submitter.getId())
                .build();

        Stock saved = stockRepository.save(stock);
        stockNotifier.approvalRequired(saved, order, submitter);
        return stockMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public StockDTOs.Response addStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request) {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        // Order lock first, then the entry: the same order as stock creation and updates
        Order order = lockOrder(findStock(id));
        Stock stock = lockApprovedStock(id);
        ensureWithinOrderQuantity(order, stock.getId(),
                stock.getUnitsPassedTest().add(stock.getDefectedUnits()).add(request.getQuantity()), "quantity");

        stock.setUnitsPassedTest(stock.getUnitsPassedTest().add(request.getQuantity()));
        stock.setAvailableUnits(stock.getAvailableUnits().add(request.getQuantity()));
        stock.setUpdatedBy(currentUserId);
        return stockMapper.toResponse(stockRepository.save(stock));
    }

    @Override
    @Transactional
    public StockDTOs.Response reduceStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request) {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        Stock stock = lockApprovedStock(id);
        if (stock.getAvailableUnits().compareTo(request.getQuantity()) < 0) {
            throw new BadRequestException("Insufficient stock available. Current available: " + stock.getAvailableUnits());
        }

        stock.setAvailableUnits(stock.getAvailableUnits().subtract(request.getQuantity()));
        stock.setUpdatedBy(currentUserId);
        return stockMapper.toResponse(stockRepository.save(stock));
    }

    @Override
    @Transactional
    public StockDTOs.Response processAdminL2Approval(Long id, StockDTOs.ApprovalDecisionRequest request) {
        User approver = currentUserProvider.getCurrentUser();
        ApprovalStatus decision = request.getDecision();
        if (decision == ApprovalStatus.PENDING) {
            throw ApiErrors.fieldError("decision", "Decision must be APPROVED or REJECTED");
        }

        // Row lock: of two reviewers deciding at once, the second sees the first one's decision
        Stock stock = stockRepository.findByIdForUpdate(id).orElseThrow(() -> stockNotFound(id));
        if (stock.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new BadRequestException("Stock request processed already with status: " + stock.getApprovalStatus());
        }

        stockNotifier.decided(stock, approver, decision, request.getRejectionReason());
        stock.setApprovalStatus(decision);
        stock.setApprovedBy(approver.getId());
        stock.setApprovedAt(LocalDateTime.now(clock));
        if (decision == ApprovalStatus.REJECTED) {
            stock.setRejectionReason(request.getRejectionReason());
            stock.setAvailableUnits(BigDecimal.ZERO);
        }
        return stockMapper.toResponse(stockRepository.save(stock));
    }

    @Override
    @Transactional
    public StockDTOs.Response updateStock(Long id, StockDTOs.UpdateRequest request) {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        Stock stock = findStock(id);

        // Every stock entry comes from one order: it must keep that order's dealer and item
        Order order = lockOrder(stock);
        if (!order.getDealer().getId().equals(request.getDealerId())) {
            throw ApiErrors.fieldError("dealerId", "Stock dealer must match its order's dealer (" + order.getDealer().getName() + ")");
        }
        if (!order.getItem().getId().equals(request.getItemId())) {
            throw ApiErrors.fieldError("itemId", "Stock item must match its order's item (" + order.getItem().getName() + ")");
        }
        ensureArrivalNotBeforeOrder(order, request.getDateOfArrival());
        ensureWithinOrderQuantity(order, stock.getId(), request.getUnitsPassedTest().add(request.getDefectedUnits()), "unitsPassedTest");

        // Units already used (by products / other services) stay used when the counts are corrected
        BigDecimal alreadyUsed = stock.getUnitsPassedTest().subtract(stock.getAvailableUnits()).max(BigDecimal.ZERO);
        BigDecimal available = request.getUnitsPassedTest().subtract(alreadyUsed);
        if (available.signum() < 0) {
            throw ApiErrors.fieldError("unitsPassedTest", "Units passed test cannot be lower than the "
                    + Quantities.plain(alreadyUsed) + " units already used from this stock entry");
        }

        stock.setBatchNumber(request.getBatchNumber());
        stock.setUnitsPassedTest(request.getUnitsPassedTest());
        stock.setDefectedUnits(request.getDefectedUnits());
        stock.setAvailableUnits(available);
        stock.setHasTested(request.getHasTested());
        stock.setDateOfArrival(request.getDateOfArrival());
        if (request.getAdditionalInfo() != null) {
            stock.setAdditionalInfo(request.getAdditionalInfo());
        }
        if (request.getIsActive() != null) {
            stock.setIsActive(request.getIsActive());
        }
        stock.setUpdatedBy(currentUserId);
        return stockMapper.toResponse(stockRepository.save(stock));
    }

    @Override
    @Transactional(readOnly = true)
    public StockDTOs.Response getStockById(Long id) {
        return stockMapper.toResponse(findStock(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockDTOs.Response> getAllStocks() {
        return stockRepository.findAll().stream().map(stockMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockDTOs.Response> getAllStocksByStatus(ApprovalStatus status) {
        return stockRepository.findByApprovalStatus(status).stream().map(stockMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StockDTOs.ItemAvailabilityResponse getItemAvailability(Long itemId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + itemId));
        List<Stock> stocks = stockRepository.findConsumableStock(itemId, CONSUMABLE_APPROVAL, CONSUMABLE_ORDER_STATUS);

        return StockDTOs.ItemAvailabilityResponse.builder()
                .itemId(item.getId())
                .itemName(item.getName())
                .itemSku(item.getSku())
                .unitOfMeasure(item.getUnitOfMeasure())
                .totalAvailableUnits(sumAvailableUnits(stocks))
                .stocks(stocks.stream().map(stockMapper::toAvailableEntry).toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockDTOs.ItemAvailabilitySummary> getItemsAvailability(List<Long> itemIds, boolean inStockOnly) {
        boolean filterByIds = itemIds != null && !itemIds.isEmpty();
        List<Item> items = filterByIds
                ? itemRepository.findAllById(itemIds)
                : itemRepository.findByIsActiveTrueOrderByNameAsc();

        Map<Long, BigDecimal> totals = (filterByIds
                ? stockRepository.sumConsumableUnitsByItem(itemIds, CONSUMABLE_APPROVAL, CONSUMABLE_ORDER_STATUS)
                : stockRepository.sumConsumableUnitsByItem(CONSUMABLE_APPROVAL, CONSUMABLE_ORDER_STATUS))
                .stream()
                .collect(Collectors.toMap(StockRepository.ItemUnitsTotal::getItemId, StockRepository.ItemUnitsTotal::getTotalUnits));

        return items.stream()
                .map(item -> StockDTOs.ItemAvailabilitySummary.builder()
                        .itemId(item.getId())
                        .itemName(item.getName())
                        .itemSku(item.getSku())
                        .unitOfMeasure(item.getUnitOfMeasure())
                        .totalAvailableUnits(totals.getOrDefault(item.getId(), BigDecimal.ZERO))
                        .build())
                .filter(summary -> !inStockOnly || summary.getTotalAvailableUnits().signum() > 0)
                .toList();
    }

    @Override
    @Transactional
    public Map<Stock, BigDecimal> consumeItemStock(Item item, BigDecimal quantity, Long currentUserId) {
        List<Stock> stocks = stockRepository.findConsumableStockForUpdate(item.getId(), CONSUMABLE_APPROVAL, CONSUMABLE_ORDER_STATUS);

        BigDecimal totalAvailable = sumAvailableUnits(stocks);
        if (totalAvailable.compareTo(quantity) < 0) {
            throw new InsufficientStockException("Insufficient stock for item '" + item.getName() + "': requested "
                    + Quantities.plain(quantity) + ", available " + Quantities.plain(totalAvailable),
                    item.getId(), quantity, totalAvailable);
        }

        // Oldest arrival first
        Map<Stock, BigDecimal> consumed = new LinkedHashMap<>();
        BigDecimal remaining = quantity;
        for (Stock stock : stocks) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal taken = stock.getAvailableUnits().min(remaining);
            stock.setAvailableUnits(stock.getAvailableUnits().subtract(taken));
            setUpdatedBy(stock, currentUserId);
            consumed.put(stock, taken);
            remaining = remaining.subtract(taken);
        }

        stockRepository.saveAll(consumed.keySet());
        return consumed;
    }

    @Override
    @Transactional
    public void restoreStock(Map<Long, BigDecimal> unitsByStockId, Long currentUserId) {
        if (unitsByStockId.isEmpty()) {
            return;
        }
        List<Stock> stocks = stockRepository.findAllByIdForUpdate(unitsByStockId.keySet());
        for (Stock stock : stocks) {
            stock.setAvailableUnits(stock.getAvailableUnits().add(unitsByStockId.get(stock.getId())));
            setUpdatedBy(stock, currentUserId);
        }
        stockRepository.saveAll(stocks);
    }

    @Override
    @Transactional
    public BigDecimal lockConsumableUnits(Long itemId) {
        return sumAvailableUnits(stockRepository.findConsumableStockForUpdate(itemId, CONSUMABLE_APPROVAL, CONSUMABLE_ORDER_STATUS));
    }

    @Override
    @Transactional
    public void takeBackStock(Map<Long, BigDecimal> unitsByStockId, Long currentUserId) {
        if (unitsByStockId.isEmpty()) {
            return;
        }
        List<Stock> stocks = stockRepository.findAllByIdForUpdate(unitsByStockId.keySet());
        // Check every entry before changing any
        for (Stock stock : stocks) {
            BigDecimal units = unitsByStockId.get(stock.getId());
            if (stock.getAvailableUnits().compareTo(units) < 0) {
                throw ApiErrors.conflict(ErrorCodes.REVERSAL_NOT_POSSIBLE, "Stock " + stock.getStockIdentityNumber()
                        + " no longer has the " + Quantities.plain(units) + " units returned to it (available "
                        + Quantities.plain(stock.getAvailableUnits()) + ")");
            }
        }
        for (Stock stock : stocks) {
            stock.setAvailableUnits(stock.getAvailableUnits().subtract(unitsByStockId.get(stock.getId())));
            setUpdatedBy(stock, currentUserId);
        }
        stockRepository.saveAll(stocks);
    }

    private Stock findStock(Long id) {
        return stockRepository.findById(id).orElseThrow(() -> stockNotFound(id));
    }

    // Quantity changes are only allowed on approved entries
    private Stock lockApprovedStock(Long id) {
        Stock stock = stockRepository.findByIdForUpdate(id).orElseThrow(() -> stockNotFound(id));
        if (stock.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw new BadRequestException("Stock operations are restricted to approved stock entries.");
        }
        return stock;
    }

    private Order lockOrder(Stock stock) {
        String orderNumber = stock.getOrder().getOrderNumber();
        return orderRepository.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with number: " + orderNumber));
    }

    /**
     * Stock is received against one order, so the units recorded on all of that order's stock entries
     * (passed + defected, rejected entries excluded) can never exceed the ordered quantity.
     */
    private void ensureWithinOrderQuantity(Order order, Long stockId, BigDecimal unitsForThisEntry, String field) {
        BigDecimal recordedElsewhere = stockRepository.sumRecordedUnitsForOrder(
                order.getOrderNumber(), stockId == null ? NO_STOCK_ENTRY : stockId, ApprovalStatus.REJECTED);
        BigDecimal allowed = order.getOrderQuantity().subtract(recordedElsewhere).max(BigDecimal.ZERO);
        if (unitsForThisEntry.compareTo(allowed) > 0) {
            String unit = order.getUnitType();
            throw ApiErrors.fieldError(field, "Stock cannot exceed the ordered quantity. Order " + order.getOrderNumber()
                    + " is for " + Quantities.plain(order.getOrderQuantity()) + " " + unit + " and "
                    + Quantities.plain(recordedElsewhere) + " " + unit + " are already recorded in other stock entries, so this "
                    + "entry can hold at most " + Quantities.plain(allowed) + " " + unit + " (units passed + defected).");
        }
    }

    private void ensureArrivalNotBeforeOrder(Order order, LocalDate dateOfArrival) {
        if (dateOfArrival != null && dateOfArrival.isBefore(order.getOrderDate())) {
            throw ApiErrors.fieldError("dateOfArrival", "Date of arrival cannot be before the order date (" + order.getOrderDate() + ")");
        }
    }

    private static ApiException orderNumberConflict(String errorCode, String message, String fieldMessage) {
        return new ApiException(message, errorCode, HttpStatus.CONFLICT, List.of(ApiErrors.error("orderNumber", fieldMessage)));
    }

    // Service-to-service movements have no procurement user; keep the last user who touched the entry
    private static void setUpdatedBy(Stock stock, Long currentUserId) {
        if (currentUserId != null) {
            stock.setUpdatedBy(currentUserId);
        }
    }

    private static BigDecimal sumAvailableUnits(List<Stock> stocks) {
        return stocks.stream().map(Stock::getAvailableUnits).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ResourceNotFoundException stockNotFound(Long id) {
        return new ResourceNotFoundException("Stock entry not found with id: " + id);
    }
}
