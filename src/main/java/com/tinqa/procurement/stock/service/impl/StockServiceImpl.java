package com.tinqa.procurement.stock.service.impl;

import com.tinqa.procurement.barcode.service.BarcodeCodes;
import com.tinqa.procurement.barcode.service.BarcodeRegistry;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.common.util.ReferenceNumberGenerator;
import com.tinqa.procurement.finance.enums.PurchasePostingReason;
import com.tinqa.procurement.finance.service.PurchasePostingRecorder;
import com.tinqa.procurement.order.payment.SupplierPaymentService;
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
import java.util.ArrayList;
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
    private final PurchasePostingRecorder purchasePostings;
    private final SupplierPaymentService supplierPayments;
    private final BarcodeRegistry barcodeRegistry;
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
        // Every passed piece is labelled: one barcode each (defected units go back to the supplier and get none)
        List<String> barcodes = barcodeRegistry.requireCodes(request.getBarcodes(),
                wholeUnits(request.getUnitsPassedTest(), "unitsPassedTest"), "barcodes", "passed unit");

        Stock stock = Stock.builder()
                .stockIdentityNumber(referenceNumbers.stockIdentityNumber())
                .batchNumber(referenceNumbers.batchNumber(order.getId()))
                .order(order)
                .supplier(order.getSupplier())
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
        // Locked once Admin L2 approves the entry
        barcodeRegistry.registerStockUnits(saved, barcodes, "barcodes", BarcodeRegistry.Actor.of(submitter), null);
        // Nothing is bought yet: the purchase is booked when Admin L2 approves the entry (processAdminL2Approval)
        stockNotifier.approvalRequired(saved, order, submitter);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public StockDTOs.Response addStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request) {
        User submitter = currentUserProvider.getCurrentUser();
        // Order lock first, then the entry: the same order as stock creation and updates
        Order order = lockOrder(findStock(id));
        Stock stock = lockApprovedStock(id);
        ensureWithinOrderQuantity(order, stock.getId(),
                stock.getUnitsPassedTest().add(stock.getDefectedUnits()).add(request.getQuantity()), "quantity");
        List<String> barcodes = barcodeRegistry.requireCodes(request.getBarcodes(),
                wholeUnits(request.getQuantity(), "quantity"), "barcodes", "added unit");

        // The units are recorded but held back from use, and not bought, until Admin L2 approves the change
        stock.setUnitsPassedTest(stock.getUnitsPassedTest().add(request.getQuantity()));
        stock.setUpdatedBy(submitter.getId());
        Stock saved = stockRepository.save(stock);
        barcodeRegistry.registerStockUnits(saved, barcodes, "barcodes", BarcodeRegistry.Actor.of(submitter), request.getReason());
        stockNotifier.changeApprovalRequired(saved, order, submitter);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public StockDTOs.Response reduceStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        Stock stock = lockApprovedStock(id);
        if (stock.getAvailableUnits().compareTo(request.getQuantity()) < 0) {
            throw new BadRequestException("Insufficient stock available. Current available: " + stock.getAvailableUnits());
        }
        // The pieces taken out are named by barcode; only units received before barcodes existed can go unnamed
        int quantity = wholeUnits(request.getQuantity(), "quantity");
        int unnamed = Math.min(quantity, barcodeRegistry.untrackedUnits(stock));
        List<String> barcodes = requireBetween(request.getBarcodes(), quantity - unnamed, quantity, "barcodes", "unit taken out");

        stock.setAvailableUnits(stock.getAvailableUnits().subtract(request.getQuantity()));
        stock.setUpdatedBy(actor.getId());
        Stock saved = stockRepository.save(stock);
        barcodeRegistry.removeStockUnits(saved, barcodes, "barcodes", BarcodeRegistry.Actor.of(actor), request.getReason());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public StockDTOs.Response processAdminL2Approval(Long id, StockDTOs.ApprovalDecisionRequest request) {
        User approver = currentUserProvider.getCurrentUser();
        ApprovalStatus decision = request.getDecision();
        if (decision == ApprovalStatus.PENDING) {
            throw ApiErrors.fieldError("decision", "Decision must be APPROVED or REJECTED");
        }

        // Order lock first (the decision books the purchase), then the entry: of two reviewers deciding at once,
        // the second sees the first one's decision
        Order order = lockOrder(findStock(id));
        Stock stock = stockRepository.findByIdForUpdate(id).orElseThrow(() -> stockNotFound(id));
        if (stock.hasChangeAwaitingApproval()) {
            return toResponse(decideChange(order, stock, approver, decision, request.getRejectionReason()));
        }
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
            barcodeRegistry.rejectStockUnits(stock, BarcodeRegistry.Actor.of(approver), request.getRejectionReason());
        } else {
            stock.setApprovedUnitsPassed(stock.getUnitsPassedTest());
            stock.setApprovedDefectedUnits(stock.getDefectedUnits());
            // The barcodes are locked from now on
            barcodeRegistry.approveStockUnits(stock, BarcodeRegistry.Actor.of(approver));
        }
        Stock saved = stockRepository.save(stock);
        // Only now does finance move: the approved passed units are bought (a rejected entry buys nothing)
        purchasePostings.rebook(order, saved, decision == ApprovalStatus.REJECTED
                ? PurchasePostingReason.STOCK_REJECTED : PurchasePostingReason.STOCK_APPROVED);
        // Testing is done: the order is now worth what passed, and an overpaid supplier owes the difference back
        supplierPayments.reconcile(order);
        return toResponse(saved);
    }

    /**
     * Admin L2's decision on a change to an approved entry. Approved: the held units become usable and the difference
     * is booked. Rejected: the counts go back to what L2 approved (units taken off come back into use) and nothing is
     * booked; the entry stays approved.
     */
    private Stock decideChange(Order order, Stock stock, User approver, ApprovalStatus decision, String reason) {
        stockNotifier.changeDecided(stock, approver, decision, reason);
        if (decision == ApprovalStatus.APPROVED) {
            stock.setAvailableUnits(stock.getAvailableUnits().add(stock.heldUnits()));
            stock.setApprovedUnitsPassed(stock.getUnitsPassedTest());
            stock.setApprovedDefectedUnits(stock.getDefectedUnits());
            stock.setApprovedBy(approver.getId());
            stock.setApprovedAt(LocalDateTime.now(clock));
            barcodeRegistry.approveStockUnits(stock, BarcodeRegistry.Actor.of(approver));
            Stock saved = stockRepository.save(stock);
            purchasePostings.rebook(order, saved, PurchasePostingReason.STOCK_CHANGE_APPROVED);
            supplierPayments.reconcile(order);
            return saved;
        }
        BigDecimal takenOff = stock.getApprovedUnitsPassed().subtract(stock.getUnitsPassedTest()).max(BigDecimal.ZERO);
        stock.setAvailableUnits(stock.getAvailableUnits().add(takenOff));
        stock.setUnitsPassedTest(stock.getApprovedUnitsPassed());
        stock.setDefectedUnits(stock.getApprovedDefectedUnits());
        // Added units' barcodes are voided, withdrawn ones come back into stock
        barcodeRegistry.rejectStockUnits(stock, BarcodeRegistry.Actor.of(approver), reason);
        return stockRepository.save(stock);
    }

    @Override
    @Transactional
    public StockDTOs.Response updateStock(Long id, StockDTOs.UpdateRequest request) {
        User submitter = currentUserProvider.getCurrentUser();
        Long currentUserId = submitter.getId();
        Stock stock = findStock(id);

        // Every stock entry comes from one order: it must keep that order's supplier and item
        Order order = lockOrder(stock);
        if (!order.getSupplier().getId().equals(request.getSupplierId())) {
            throw ApiErrors.fieldError("supplierId", "Stock supplier must match its order's supplier (" + order.getSupplier().getName() + ")");
        }
        if (!order.getItem().getId().equals(request.getItemId())) {
            throw ApiErrors.fieldError("itemId", "Stock item must match its order's item (" + order.getItem().getName() + ")");
        }
        ensureArrivalNotBeforeOrder(order, request.getDateOfArrival());
        ensureWithinOrderQuantity(order, stock.getId(), request.getUnitsPassedTest().add(request.getDefectedUnits()), "unitsPassedTest");

        // Units already used (by products / other services) stay used when the counts are corrected. On an approved
        // entry only the approved units are usable: passed units above them are held until L2 approves the change.
        BigDecimal alreadyUsed = usableUnits(stock, stock.getUnitsPassedTest()).subtract(stock.getAvailableUnits()).max(BigDecimal.ZERO);
        if (request.getUnitsPassedTest().compareTo(alreadyUsed) < 0) {
            throw ApiErrors.fieldError("unitsPassedTest", "Units passed test cannot be lower than the "
                    + Quantities.plain(alreadyUsed) + " units already used from this stock entry");
        }
        BigDecimal available = usableUnits(stock, request.getUnitsPassedTest()).subtract(alreadyUsed);
        boolean countsChanged = stock.getUnitsPassedTest().compareTo(request.getUnitsPassedTest()) != 0
                || stock.getDefectedUnits().compareTo(request.getDefectedUnits()) != 0;
        PassedUnitBarcodes barcodes = passedUnitBarcodes(stock, request);

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
        Stock saved = stockRepository.save(stock);
        BarcodeRegistry.Actor actor = BarcodeRegistry.Actor.of(submitter);
        barcodeRegistry.withdrawStockUnits(saved, barcodes.removed(), "removedBarcodes", actor, "Passed units corrected");
        barcodeRegistry.registerStockUnits(saved, barcodes.added(), "addedBarcodes", actor, "Passed units corrected");
        // Barcodes added or taken off an approved entry wait for L2 with its changed counts. Without one, swapping the
        // barcodes of approved units is a correction of locked barcodes, which a Super Admin decides
        if (saved.getApprovalStatus() == ApprovalStatus.APPROVED && !saved.hasChangeAwaitingApproval()
                && barcodeRegistry.hasUnitsAwaitingDecision(saved)) {
            throw ApiErrors.fieldError("addedBarcodes", "Approved barcodes can't be swapped here: ask for a barcode correction instead");
        }
        // Finance is untouched: a new entry is booked when L2 approves it, a corrected approved one when L2 approves the change
        if (countsChanged && saved.hasChangeAwaitingApproval()) {
            stockNotifier.changeApprovalRequired(saved, order, submitter);
        }
        return toResponse(saved);
    }

    /** Barcodes of the passed units an update adds and takes off, checked against the change in passed units. */
    private record PassedUnitBarcodes(List<String> added, List<String> removed) {
    }

    private PassedUnitBarcodes passedUnitBarcodes(Stock stock, StockDTOs.UpdateRequest request) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        List<String> added = BarcodeCodes.normalizeAll(request.getAddedBarcodes(), "addedBarcodes", errors);
        List<String> removed = BarcodeCodes.normalizeAll(request.getRemovedBarcodes(), "removedBarcodes", errors);
        ApiErrors.throwIfAny(errors);
        if (stock.getApprovalStatus() == ApprovalStatus.REJECTED) {
            // A rejected entry holds nothing usable; its barcodes were voided with it
            if (!added.isEmpty() || !removed.isEmpty()) {
                throw ApiErrors.fieldError("addedBarcodes", "A rejected stock entry has no barcodes to change");
            }
            return new PassedUnitBarcodes(List.of(), List.of());
        }
        int change = wholeUnits(request.getUnitsPassedTest(), "unitsPassedTest") - wholeUnits(stock.getUnitsPassedTest(), "unitsPassedTest");
        if (change == 0 && added.isEmpty() && removed.isEmpty()) {
            return new PassedUnitBarcodes(List.of(), List.of());
        }
        // Units taken off without naming a barcode: only possible for units that never had one
        int unnamed = added.size() - removed.size() - change;
        if (unnamed < 0) {
            throw barcodesRequired("addedBarcodes", "Enter a barcode for each added passed unit: " + (-unnamed) + " more needed");
        }
        int untracked = barcodeRegistry.untrackedPassedUnits(stock);
        if (stock.getApprovalStatus() == ApprovalStatus.APPROVED) {
            untracked = Math.min(untracked, barcodeRegistry.untrackedUnits(stock));
        }
        if (unnamed > untracked) {
            throw barcodesRequired("removedBarcodes", "Enter the barcodes of the passed units taken off: "
                    + (unnamed - untracked) + " more needed");
        }
        return new PassedUnitBarcodes(added, removed);
    }

    @Override
    @Transactional(readOnly = true)
    public StockDTOs.Response getStockById(Long id) {
        return toResponse(findStock(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockDTOs.Response> getAllStocks() {
        return toResponses(stockRepository.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockDTOs.Response> getAllStocksByStatus(ApprovalStatus status) {
        // PENDING is Admin L2's queue: new entries and changes to approved entries
        List<Stock> stocks = status == ApprovalStatus.PENDING
                ? stockRepository.findAwaitingApproval(ApprovalStatus.PENDING, ApprovalStatus.APPROVED)
                : stockRepository.findByApprovalStatus(status);
        return toResponses(stocks);
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
        // Pieces with a barcode are only ever used by naming it (consumeUnits); this takes the ones without
        Map<Long, BigDecimal> untracked = barcodeRegistry.untrackedUnits(stocks);

        BigDecimal totalAvailable = stocks.stream().map(stock -> untracked.get(stock.getId())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAvailable.compareTo(quantity) < 0) {
            String barcoded = totalAvailable.compareTo(sumAvailableUnits(stocks)) < 0
                    ? " without a barcode (pieces with one are used by scanning their barcode)" : "";
            throw new InsufficientStockException("Insufficient stock for item '" + item.getName() + "': requested "
                    + Quantities.plain(quantity) + ", available " + Quantities.plain(totalAvailable) + barcoded,
                    item.getId(), quantity, totalAvailable);
        }

        // Oldest arrival first
        Map<Stock, BigDecimal> consumed = new LinkedHashMap<>();
        BigDecimal remaining = quantity;
        for (Stock stock : stocks) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal taken = untracked.get(stock.getId()).min(remaining);
            if (taken.signum() == 0) {
                continue;
            }
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
    public Map<Stock, BigDecimal> consumeUnits(Map<Long, BigDecimal> unitsByStockId, Long currentUserId) {
        Map<Stock, BigDecimal> consumed = new LinkedHashMap<>();
        if (unitsByStockId.isEmpty()) {
            return consumed;
        }
        List<Stock> stocks = stockRepository.findAllByIdForUpdate(unitsByStockId.keySet());
        for (Stock stock : stocks) {
            BigDecimal units = unitsByStockId.get(stock.getId());
            boolean consumable = Boolean.TRUE.equals(stock.getIsActive()) && stock.getApprovalStatus() == CONSUMABLE_APPROVAL
                    && stock.getOrder().getOrderStatus() == CONSUMABLE_ORDER_STATUS;
            if (!consumable || stock.getAvailableUnits().compareTo(units) < 0) {
                throw ApiErrors.conflict(ErrorCodes.INSUFFICIENT_ITEM_STOCK, "Stock " + stock.getStockIdentityNumber()
                        + " can't supply the " + Quantities.plain(units) + " pieces scanned from it"
                        + (consumable ? " (available " + Quantities.plain(stock.getAvailableUnits()) + ")" : ": it is not usable"));
            }
        }
        for (Stock stock : stocks) {
            BigDecimal units = unitsByStockId.get(stock.getId());
            stock.setAvailableUnits(stock.getAvailableUnits().subtract(units));
            setUpdatedBy(stock, currentUserId);
            consumed.put(stock, units);
        }
        stockRepository.saveAll(stocks);
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
    public BigDecimal lockUntrackedConsumableUnits(Long itemId) {
        List<Stock> stocks = stockRepository.findConsumableStockForUpdate(itemId, CONSUMABLE_APPROVAL, CONSUMABLE_ORDER_STATUS);
        return barcodeRegistry.untrackedUnits(stocks).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
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

    // The passed units that can be used for a count of passed units: on an approved entry never more than L2 approved
    private static BigDecimal usableUnits(Stock stock, BigDecimal unitsPassed) {
        if (stock.getApprovalStatus() == ApprovalStatus.APPROVED && stock.getApprovedUnitsPassed() != null) {
            return unitsPassed.min(stock.getApprovedUnitsPassed());
        }
        return unitsPassed;
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

    private StockDTOs.Response toResponse(Stock stock) {
        return toResponses(List.of(stock)).getFirst();
    }

    // With each entry's available units that have no barcode yet (one query for the whole list)
    private List<StockDTOs.Response> toResponses(List<Stock> stocks) {
        Map<Long, BigDecimal> untracked = barcodeRegistry.untrackedUnits(stocks);
        return stocks.stream().map(stock -> {
            StockDTOs.Response response = stockMapper.toResponse(stock);
            response.setUnitsWithoutBarcode(stock.getApprovalStatus() == ApprovalStatus.APPROVED
                    ? untracked.getOrDefault(stock.getId(), BigDecimal.ZERO) : BigDecimal.ZERO);
            return response;
        }).toList();
    }

    // Pieces are labelled one by one, so a count that carries barcodes must be whole
    private static int wholeUnits(BigDecimal units, String field) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        int whole = BarcodeCodes.wholeUnits(units, field, errors);
        ApiErrors.throwIfAny(errors);
        return whole;
    }

    // Between min and max valid, distinct codes
    private List<String> requireBetween(List<String> raw, int min, int max, String field, String what) {
        if (min == max) {
            return barcodeRegistry.requireCodes(raw, max, field, what);
        }
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        List<String> codes = BarcodeCodes.normalizeAll(raw, field, errors);
        ApiErrors.throwIfAny(errors);
        if (codes.size() < min || codes.size() > max) {
            throw barcodesRequired(field, "Enter the barcodes of " + min + " to " + max + " " + what + "s ("
                    + codes.size() + " entered); only pieces received before barcodes were recorded can go unnamed");
        }
        return codes;
    }

    private static ApiException barcodesRequired(String field, String message) {
        return new ApiException(message, ErrorCodes.BARCODES_REQUIRED, HttpStatus.BAD_REQUEST, List.of(ApiErrors.error(field, message)));
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
