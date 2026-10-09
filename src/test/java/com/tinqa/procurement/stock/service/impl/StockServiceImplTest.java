package com.tinqa.procurement.stock.service.impl;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.exception.InsufficientStockException;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.stock.dto.StockDTOs;
import com.tinqa.procurement.stock.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceImplTest {

    private static final Long USER_ID = 7L;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private StockServiceImpl stockService;

    private final Item item = Item.builder().id(1L).name("Motherboard").sku("MB-1").build();

    @Test
    void consumesOldestStockFirstAndSplitsAcrossEntries() {
        Stock oldest = stock(10L, "1");
        Stock newer = stock(11L, "5");
        when(stockRepository.findConsumableStockForUpdate(1L, ApprovalStatus.APPROVED, OrderStatus.DELIVERED))
                .thenReturn(List.of(oldest, newer));

        Map<Stock, BigDecimal> consumed = stockService.consumeItemStock(item, new BigDecimal("3"), USER_ID);

        assertEquals(2, consumed.size());
        assertEquals(0, new BigDecimal("1").compareTo(consumed.get(oldest)));
        assertEquals(0, new BigDecimal("2").compareTo(consumed.get(newer)));
        assertEquals(0, BigDecimal.ZERO.compareTo(oldest.getAvailableUnits()));
        assertEquals(0, new BigDecimal("3").compareTo(newer.getAvailableUnits()));
        assertEquals(USER_ID, newer.getUpdatedBy());
    }

    @Test
    void doesNotTouchLaterEntriesWhenFirstEntryCoversQuantity() {
        Stock oldest = stock(10L, "4");
        Stock newer = stock(11L, "5");
        when(stockRepository.findConsumableStockForUpdate(1L, ApprovalStatus.APPROVED, OrderStatus.DELIVERED))
                .thenReturn(List.of(oldest, newer));

        Map<Stock, BigDecimal> consumed = stockService.consumeItemStock(item, new BigDecimal("4"), USER_ID);

        assertEquals(1, consumed.size());
        assertEquals(0, new BigDecimal("5").compareTo(newer.getAvailableUnits()));
    }

    @Test
    void insufficientStockThrowsWithoutChangingAnything() {
        Stock only = stock(10L, "1");
        when(stockRepository.findConsumableStockForUpdate(1L, ApprovalStatus.APPROVED, OrderStatus.DELIVERED))
                .thenReturn(List.of(only));

        InsufficientStockException exception = assertThrows(InsufficientStockException.class,
                () -> stockService.consumeItemStock(item, new BigDecimal("2"), USER_ID));

        assertEquals(InsufficientStockException.ERROR_CODE, exception.getErrorCode());
        assertEquals(0, new BigDecimal("2").compareTo(exception.getRequestedUnits()));
        assertEquals(0, BigDecimal.ONE.compareTo(exception.getAvailableUnits()));
        assertEquals(0, BigDecimal.ONE.compareTo(only.getAvailableUnits()));
        verify(stockRepository, never()).saveAll(any());
    }

    @Test
    void noConsumableStockReportsZeroAvailable() {
        when(stockRepository.findConsumableStockForUpdate(1L, ApprovalStatus.APPROVED, OrderStatus.DELIVERED))
                .thenReturn(List.of());

        InsufficientStockException exception = assertThrows(InsufficientStockException.class,
                () -> stockService.consumeItemStock(item, BigDecimal.ONE, USER_ID));

        assertEquals(0, BigDecimal.ZERO.compareTo(exception.getAvailableUnits()));
    }

    @Test
    void restoreAddsUnitsBackToEachStockEntry() {
        Stock first = stock(10L, "0");
        Stock second = stock(11L, "3");
        when(stockRepository.findAllByIdForUpdate(any())).thenReturn(List.of(first, second));

        stockService.restoreStock(Map.of(10L, new BigDecimal("1"), 11L, new BigDecimal("2")), USER_ID);

        assertEquals(0, new BigDecimal("1").compareTo(first.getAvailableUnits()));
        assertEquals(0, new BigDecimal("5").compareTo(second.getAvailableUnits()));
    }

    private Stock stock(Long id, String availableUnits) {
        return Stock.builder()
                .id(id)
                .item(item)
                .availableUnits(new BigDecimal(availableUnits))
                .build();
    }

    // ---------- stock can never exceed its order's quantity ----------

    private Order order(String quantity) {
        Dealer dealer = new Dealer();
        dealer.setId(3L);
        dealer.setName("Summit");
        return Order.builder().id(5L).orderNumber("ORD-1").dealer(dealer).item(item).orderQuantity(new BigDecimal(quantity))
                .unitType("PCS").orderDate(LocalDate.now().minusDays(5)).build();
    }

    private void recordedElsewhere(Order order, String units) {
        when(orderRepository.findByOrderNumberForUpdate("ORD-1")).thenReturn(Optional.of(order));
        lenient().when(stockRepository.sumRecordedUnitsForOrder(eq("ORD-1"), anyLong(), eq(ApprovalStatus.REJECTED)))
                .thenReturn(new BigDecimal(units));
    }

    @Test
    void createRejectsUnitsBeyondWhatTheOrderHasLeft() {
        recordedElsewhere(order("10"), "8");
        StockDTOs.CreateFromOrderRequest request = StockDTOs.CreateFromOrderRequest.builder().orderNumber("ORD-1")
                .unitsPassedTest(new BigDecimal("2")).defectedUnits(BigDecimal.ONE).hasTested(true).dateOfArrival(LocalDate.now()).build();

        ApiException exception = assertThrows(ApiException.class, () -> stockService.createStockFromOrder(request, USER_ID));

        assertEquals("Stock cannot exceed the ordered quantity. Order ORD-1 is for 10 PCS and 8 PCS are already recorded in other "
                + "stock entries, so this entry can hold at most 2 PCS (units passed + defected).", exception.getMessage());
        assertEquals("unitsPassedTest", exception.getErrors().getFirst().getField());
        verify(stockRepository, never()).save(any());
    }

    @Test
    void createRecordsTheSubmittedPassedUnits() {
        recordedElsewhere(order("10"), "0");
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        StockDTOs.CreateFromOrderRequest request = StockDTOs.CreateFromOrderRequest.builder().orderNumber("ORD-1")
                .unitsPassedTest(new BigDecimal("6")).defectedUnits(BigDecimal.ONE).hasTested(true).dateOfArrival(LocalDate.now()).build();

        StockDTOs.Response response = stockService.createStockFromOrder(request, USER_ID);

        assertEquals(0, new BigDecimal("6").compareTo(response.getUnitsPassedTest()));
        assertEquals(0, new BigDecimal("6").compareTo(response.getAvailableUnits()));
    }

    @Test
    void submittedStockNotifiesAdminL2WithItsDetails() {
        recordedElsewhere(order("10"), "0");
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                com.tinqa.procurement.security.model.User.builder().id(USER_ID).username("admin_l1").build()));
        StockDTOs.CreateFromOrderRequest request = StockDTOs.CreateFromOrderRequest.builder().orderNumber("ORD-1")
                .unitsPassedTest(new BigDecimal("6")).defectedUnits(BigDecimal.ONE).hasTested(true).dateOfArrival(LocalDate.now()).build();

        stockService.createStockFromOrder(request, USER_ID);

        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).createForRole(eq(Role.ADMIN_L2), eq(USER_ID), eq("Stock Approval Required"), message.capture());
        assertTrue(message.getValue().contains("for order ORD-1: 6 PCS passed and 1 PCS defected of Motherboard from `Summit`"),
                message.getValue());
        assertTrue(message.getValue().endsWith("Submitted by admin_l1 and waiting for your approval."), message.getValue());
    }

    @Test
    void arrivalCannotBeBeforeTheOrderDate() {
        Order order = order("10");
        recordedElsewhere(order, "0");
        StockDTOs.CreateFromOrderRequest request = StockDTOs.CreateFromOrderRequest.builder().orderNumber("ORD-1")
                .unitsPassedTest(BigDecimal.ONE).defectedUnits(BigDecimal.ZERO).hasTested(true)
                .dateOfArrival(order.getOrderDate().minusDays(1)).build();

        ApiException exception = assertThrows(ApiException.class, () -> stockService.createStockFromOrder(request, USER_ID));
        assertEquals("dateOfArrival", exception.getErrors().getFirst().getField());
    }

    @Test
    void addingQuantityCannotPushTheEntryPastTheOrder() {
        Order order = order("6");
        recordedElsewhere(order, "0");
        Stock entry = approvedEntry(order, "5", "5");
        when(stockRepository.findById(10L)).thenReturn(Optional.of(entry));

        assertThrows(ApiException.class, () -> stockService.addStockQuantity(10L,
                StockDTOs.QuantityAdjustmentRequest.builder().quantity(new BigDecimal("2")).reason("recount").build(), USER_ID));
        assertEquals(0, new BigDecimal("5").compareTo(entry.getUnitsPassedTest()));
    }

    @Test
    void correctingCountsKeepsAlreadyUsedUnitsUsed() {
        Order order = order("10");
        recordedElsewhere(order, "0");
        Stock entry = approvedEntry(order, "10", "2"); // 8 already used by products
        when(stockRepository.findById(10L)).thenReturn(Optional.of(entry));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        stockService.updateStock(10L, update("9"), USER_ID);

        assertEquals(0, BigDecimal.ONE.compareTo(entry.getAvailableUnits()));
    }

    @Test
    void passedUnitsCannotDropBelowWhatWasAlreadyUsed() {
        Order order = order("10");
        recordedElsewhere(order, "0");
        when(stockRepository.findById(10L)).thenReturn(Optional.of(approvedEntry(order, "10", "2")));

        ApiException exception = assertThrows(ApiException.class, () -> stockService.updateStock(10L, update("7"), USER_ID));
        assertTrue(exception.getMessage().contains("8 units already used"));
    }

    @Test
    void stockMustKeepItsOrdersItem() {
        Order order = order("10");
        recordedElsewhere(order, "0");
        when(stockRepository.findById(10L)).thenReturn(Optional.of(approvedEntry(order, "10", "10")));
        StockDTOs.UpdateRequest request = update("10");
        request.setItemId(99L);

        ApiException exception = assertThrows(ApiException.class, () -> stockService.updateStock(10L, request, USER_ID));
        assertEquals("itemId", exception.getErrors().getFirst().getField());
    }

    private Stock approvedEntry(Order order, String passed, String available) {
        return Stock.builder().id(10L).order(order).dealer(order.getDealer()).item(item)
                .unitsPassedTest(new BigDecimal(passed)).defectedUnits(BigDecimal.ZERO).availableUnits(new BigDecimal(available))
                .approvalStatus(ApprovalStatus.APPROVED).dateOfArrival(LocalDate.now()).build();
    }

    private StockDTOs.UpdateRequest update(String passed) {
        return StockDTOs.UpdateRequest.builder().batchNumber("BAT-1").dealerId(3L).itemId(1L)
                .unitsPassedTest(new BigDecimal(passed)).defectedUnits(BigDecimal.ZERO).hasTested(true)
                .dateOfArrival(LocalDate.now()).build();
    }

    @Test
    void anOrderCanBeUsedForOnlyOneStockEntry() {
        Order order = order("10");
        recordedElsewhere(order, "0");
        when(stockRepository.findFirstByOrderOrderNumber("ORD-1"))
                .thenReturn(Optional.of(Stock.builder().id(1L).stockIdentityNumber("STK-1").build()));
        StockDTOs.CreateFromOrderRequest request = StockDTOs.CreateFromOrderRequest.builder().orderNumber("ORD-1")
                .unitsPassedTest(BigDecimal.ONE).defectedUnits(BigDecimal.ZERO).hasTested(true).dateOfArrival(LocalDate.now()).build();

        ApiException exception = assertThrows(ApiException.class, () -> stockService.createStockFromOrder(request, USER_ID));

        assertEquals("ORDER_ALREADY_HAS_STOCK", exception.getErrorCode());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getStatus());
        verify(stockRepository, never()).save(any());
    }
}
