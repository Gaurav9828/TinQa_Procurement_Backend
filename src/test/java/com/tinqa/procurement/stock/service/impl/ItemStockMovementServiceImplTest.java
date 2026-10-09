package com.tinqa.procurement.stock.service.impl;

import com.tinqa.procurement.common.exception.ApiException;
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
import com.tinqa.procurement.stock.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ItemStockMovementServiceImplTest {

    private static final String SOURCE = "ECOMMERCE_PRODUCT";
    private static final String REFERENCE = "product:42";

    @Mock private ItemStockMovementRepository movementRepository;
    @Mock private ItemStockMovementAllocationRepository allocationRepository;
    @Mock private StockRepository stockRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private StockService stockService;

    @InjectMocks private ItemStockMovementServiceImpl service;

    private final Item motherboard = Item.builder().id(7L).name("Motherboard").build();
    private final Item cable = Item.builder().id(8L).name("Cable").build();

    @BeforeEach
    void setUp() {
        when(itemRepository.findAllById(any())).thenAnswer(invocation -> {
            List<Item> found = new ArrayList<>();
            for (Object id : (Iterable<?>) invocation.getArgument(0)) {
                if (id.equals(7L)) found.add(motherboard);
                if (id.equals(8L)) found.add(cable);
            }
            return found;
        });
        when(movementRepository.findByMovementId(any())).thenReturn(Optional.empty());
        when(movementRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(movementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stockRepository.getReferenceById(anyLong())).thenAnswer(invocation -> stock(invocation.getArgument(0)));
    }

    @Test
    void consumingLineRecordsAllocationsPerBatch() {
        when(stockService.lockConsumableUnits(7L)).thenReturn(new BigDecimal("10"));
        Map<Stock, BigDecimal> consumed = new LinkedHashMap<>();
        consumed.put(stock(1L), new BigDecimal("3"));
        consumed.put(stock(2L), new BigDecimal("1"));
        when(stockService.consumeItemStock(motherboard, new BigDecimal("4"), null)).thenReturn(consumed);

        var result = service.applyMovement(request(UUID.randomUUID(), line(7L, "4")));

        assertEquals(ItemStockMovementStatus.APPLIED, result.getStatus());
        assertEquals(1, result.getLines().size());
        assertEquals(0, new BigDecimal("4").compareTo(result.getLines().getFirst().getQuantity()));
        assertEquals(2, result.getLines().getFirst().getAllocations().size());
    }

    @Test
    void insufficientStockReportsEveryShortLineAndMovesNothing() {
        when(stockService.lockConsumableUnits(7L)).thenReturn(new BigDecimal("2"));
        when(stockService.lockConsumableUnits(8L)).thenReturn(BigDecimal.ZERO);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.applyMovement(request(UUID.randomUUID(), line(7L, "4"), line(8L, "1"))));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("INSUFFICIENT_ITEM_STOCK", exception.getErrorCode());
        assertEquals("Not enough stock for Motherboard: need 4, available 2; Not enough stock for Cable: need 1, available 0",
                exception.getMessage());
        assertEquals(List.of("lines[0].quantity", "lines[1].quantity"),
                exception.getErrors().stream().map(e -> e.getField()).toList());
        verify(stockService, never()).consumeItemStock(any(), any(), any());
    }

    @Test
    void returnGoesBackToMostRecentlyConsumedBatchesFirst() {
        when(allocationRepository.findHeldUnitsByStock(SOURCE, REFERENCE, 7L, ItemStockMovementStatus.APPLIED))
                .thenReturn(List.of(held(11L, "2"), held(10L, "3")));

        service.applyMovement(request(UUID.randomUUID(), line(7L, "-3")));

        Map<Long, BigDecimal> expected = new LinkedHashMap<>();
        expected.put(11L, new BigDecimal("2"));
        expected.put(10L, new BigDecimal("1"));
        verify(stockService).restoreStock(expected, null);
    }

    @Test
    void returningMoreThanReferenceConsumedIsRejected() {
        when(allocationRepository.findHeldUnitsByStock(SOURCE, REFERENCE, 7L, ItemStockMovementStatus.APPLIED))
                .thenReturn(List.of(held(10L, "1")));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.applyMovement(request(UUID.randomUUID(), line(7L, "-2"))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("RETURN_EXCEEDS_CONSUMED", exception.getErrorCode());
        verify(stockService, never()).restoreStock(any(), any());
    }

    @Test
    void retryWithSameMovementIdReturnsOriginalWithoutMovingStock() {
        UUID movementId = UUID.randomUUID();
        when(movementRepository.findByMovementId(movementId)).thenReturn(Optional.of(ItemStockMovement.builder()
                .movementId(movementId).source(SOURCE).reference(REFERENCE).status(ItemStockMovementStatus.APPLIED).build()));

        var result = service.applyMovement(request(movementId, line(7L, "4")));

        assertEquals(movementId, result.getMovementId());
        verifyNoInteractions(stockService);
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void reusingMovementIdForAnotherReferenceIsAConflict() {
        UUID movementId = UUID.randomUUID();
        when(movementRepository.findByMovementId(movementId)).thenReturn(Optional.of(ItemStockMovement.builder()
                .movementId(movementId).source(SOURCE).reference("product:99").status(ItemStockMovementStatus.APPLIED).build()));

        ApiException exception = assertThrows(ApiException.class, () -> service.applyMovement(request(movementId, line(7L, "1"))));

        assertEquals("MOVEMENT_ID_CONFLICT", exception.getErrorCode());
    }

    @Test
    void zeroAndDuplicateLinesAreRejected() {
        ApiException exception = assertThrows(ApiException.class,
                () -> service.applyMovement(request(UUID.randomUUID(), line(7L, "0"), line(7L, "1"))));

        assertEquals("INVALID_MOVEMENT_LINES", exception.getErrorCode());
        assertEquals(2, exception.getErrors().size());
    }

    @Test
    void reverseRestoresConsumedAndTakesBackReturnedUnits() {
        UUID movementId = UUID.randomUUID();
        ItemStockMovement movement = ItemStockMovement.builder()
                .movementId(movementId).source(SOURCE).reference(REFERENCE).status(ItemStockMovementStatus.APPLIED).build();
        movement.getAllocations().add(allocation(movement, 7L, 1L, "3"));
        movement.getAllocations().add(allocation(movement, 8L, 5L, "-2"));
        when(movementRepository.findByMovementIdForUpdate(movementId)).thenReturn(Optional.of(movement));
        when(allocationRepository.findHeldUnitsByStock(SOURCE, REFERENCE, 7L, ItemStockMovementStatus.APPLIED))
                .thenReturn(List.of(held(1L, "3")));

        var result = service.reverseMovement(movementId, null);

        assertEquals(ItemStockMovementStatus.REVERSED, result.getStatus());
        verify(stockService).restoreStock(Map.of(1L, new BigDecimal("3")), null);
        verify(stockService).takeBackStock(Map.of(5L, new BigDecimal("2")), null);
    }

    @Test
    void reverseIsBlockedWhenALaterMovementAlreadyReturnedTheUnits() {
        UUID movementId = UUID.randomUUID();
        ItemStockMovement movement = ItemStockMovement.builder()
                .movementId(movementId).source(SOURCE).reference(REFERENCE).status(ItemStockMovementStatus.APPLIED).build();
        movement.getAllocations().add(allocation(movement, 7L, 1L, "3"));
        when(movementRepository.findByMovementIdForUpdate(movementId)).thenReturn(Optional.of(movement));
        when(allocationRepository.findHeldUnitsByStock(SOURCE, REFERENCE, 7L, ItemStockMovementStatus.APPLIED))
                .thenReturn(List.of(held(1L, "1")));

        ApiException exception = assertThrows(ApiException.class, () -> service.reverseMovement(movementId, null));

        assertEquals("REVERSAL_NOT_POSSIBLE", exception.getErrorCode());
        verify(stockService, never()).restoreStock(any(), any());
    }

    @Test
    void reversingAnAlreadyReversedMovementChangesNothing() {
        UUID movementId = UUID.randomUUID();
        when(movementRepository.findByMovementIdForUpdate(movementId)).thenReturn(Optional.of(ItemStockMovement.builder()
                .movementId(movementId).source(SOURCE).reference(REFERENCE).status(ItemStockMovementStatus.REVERSED).build()));

        var result = service.reverseMovement(movementId, null);

        assertEquals(ItemStockMovementStatus.REVERSED, result.getStatus());
        verifyNoInteractions(stockService);
    }

    @Test
    void reversingUnknownMovementRecordsPlaceholderThatBlocksLateApply() {
        UUID movementId = UUID.randomUUID();
        when(movementRepository.findByMovementIdForUpdate(movementId)).thenReturn(Optional.empty());
        ArgumentCaptor<ItemStockMovement> saved = ArgumentCaptor.forClass(ItemStockMovement.class);

        service.reverseMovement(movementId, ItemStockMovementDTOs.ReverseRequest.builder().source(SOURCE).reference(REFERENCE).build());

        verify(movementRepository).saveAndFlush(saved.capture());
        assertEquals(ItemStockMovementStatus.REVERSED, saved.getValue().getStatus());

        when(movementRepository.findByMovementId(movementId)).thenReturn(Optional.of(saved.getValue()));
        var lateApply = service.applyMovement(request(movementId, line(7L, "4")));

        assertEquals(ItemStockMovementStatus.REVERSED, lateApply.getStatus());
        verifyNoInteractions(stockService);
    }

    private ItemStockMovementDTOs.MovementRequest request(UUID movementId, ItemStockMovementDTOs.Line... lines) {
        return ItemStockMovementDTOs.MovementRequest.builder()
                .movementId(movementId).source(SOURCE).reference(REFERENCE).lines(List.of(lines)).performedBy("admin@tinqa.com")
                .build();
    }

    private ItemStockMovementDTOs.Line line(Long itemId, String quantity) {
        return ItemStockMovementDTOs.Line.builder().itemId(itemId).quantity(new BigDecimal(quantity)).build();
    }

    private Stock stock(Long id) {
        return Stock.builder().id(id).batchNumber("BAT-" + id).build();
    }

    private ItemStockMovementAllocation allocation(ItemStockMovement movement, Long itemId, Long stockId, String units) {
        return ItemStockMovementAllocation.builder()
                .movement(movement).lineIndex(0).itemId(itemId).stock(stock(stockId)).units(new BigDecimal(units)).build();
    }

    private ItemStockMovementAllocationRepository.StockNetUnits held(Long stockId, String units) {
        return new ItemStockMovementAllocationRepository.StockNetUnits() {
            public Long getStockId() { return stockId; }
            public BigDecimal getNetUnits() { return new BigDecimal(units); }
            public Long getLastAllocationId() { return stockId; }
        };
    }
}
