package com.tinqa.procurement.notification.service.impl;

import com.tinqa.procurement.notification.repository.NotificationRepository;
import com.tinqa.procurement.notification.repository.NotificationRecipientRepository;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationRecipientRepository notificationRecipientRepository;
    @Mock private UserRepository userRepository;
    @Mock private com.tinqa.procurement.notification.mapper.NotificationMapper notificationMapper;

    @InjectMocks private NotificationServiceImpl service;

    @Test
    void blankNotificationsAreNeverStored() {
        service.createForUser(2L, "", "");
        service.createForUser(2L, "Title", "  ");
        service.createBroadcast(" ", "message");

        verifyNoInteractions(notificationRepository, notificationRecipientRepository);
    }

    @Test
    void roleNotificationSkipsThePersonWhoActed() {
        User actor = User.builder().id(1L).role(Role.ADMIN_L2).build();
        User other = User.builder().id(2L).role(Role.ADMIN_L2).build();
        when(userRepository.findByRoleAndEnabledTrue(Role.ADMIN_L2)).thenReturn(List.of(actor, other));
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(notificationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createForRole(Role.ADMIN_L2, 1L, "Stock Approval Required", "Stock STK-1 is waiting");

        verify(userRepository, never()).findById(1L);
        verify(notificationRecipientRepository, times(1)).save(any());
    }
}
