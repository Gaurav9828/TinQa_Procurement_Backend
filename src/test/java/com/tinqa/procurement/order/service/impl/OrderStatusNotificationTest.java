package com.tinqa.procurement.order.service.impl;

import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderStatusNotificationTest {

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks private OrderServiceImpl service;

    private Order order() {
        Dealer dealer = new Dealer();
        dealer.setId(3L);
        dealer.setName("Computernoics Lab");
        return Order.builder().id(5L).orderNumber("ORD-1").dealer(dealer)
                .item(Item.builder().id(1L).name("Summit X Mother Board").build())
                .orderQuantity(new BigDecimal("10.000")).unitType("METER").orderDate(LocalDate.of(2026, 10, 1))
                .orderStatus(OrderStatus.CONFIRMED).updatedBy(2L).build();
    }

    private ArgumentCaptor<String>[] changeStatus(OrderStatus status) {
        Order order = order();
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).username("admin_l1").build()));

        service.updateOrderStatus(5L, OrderDTOs.UpdateStatusRequest.builder().status(status)
                .actualDelivery(status == OrderStatus.DELIVERED ? LocalDate.of(2026, 10, 9) : null).build(), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String>[] captors = new ArgumentCaptor[]{ArgumentCaptor.forClass(String.class), ArgumentCaptor.forClass(String.class)};
        verify(notificationService).createForUser(eq(2L), captors[0].capture(), captors[1].capture());
        return captors;
    }

    @Test
    void deliveredOrderNotificationHasContent() {
        ArgumentCaptor<String>[] sent = changeStatus(OrderStatus.DELIVERED);

        assertEquals("Order Delivered", sent[0].getValue());
        assertEquals("Order ORD-1 (10 METER of Summit X Mother Board from `Computernoics Lab`) was marked as delivered by admin_l1"
                + " on 2026-10-09. Stock can now be created from it.", sent[1].getValue());
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void everyStatusChangeProducesATitleAndMessage(OrderStatus status) {
        ArgumentCaptor<String>[] sent = changeStatus(status);

        assertFalse(sent[0].getValue().isBlank(), status + " title");
        assertTrue(sent[1].getValue().startsWith("Order ORD-1 "), status + " message");
    }

    @Test
    void nobodyIsNotifiedAboutTheirOwnChange() {
        Order order = order();
        order.setUpdatedBy(1L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).username("admin_l1").build()));

        service.updateOrderStatus(5L, OrderDTOs.UpdateStatusRequest.builder().status(OrderStatus.SHIPPED).build(), 1L);

        verifyNoInteractions(notificationService);
    }
}
