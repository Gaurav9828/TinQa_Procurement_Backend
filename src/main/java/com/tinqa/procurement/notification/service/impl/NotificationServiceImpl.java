package com.tinqa.procurement.notification.service.impl;

import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.notification.dto.NotificationResponse;
import com.tinqa.procurement.notification.entity.Notification;
import com.tinqa.procurement.notification.entity.NotificationRecipient;
import com.tinqa.procurement.notification.mapper.NotificationMapper;
import com.tinqa.procurement.notification.repository.NotificationRecipientRepository;
import com.tinqa.procurement.notification.repository.NotificationRepository;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository notificationRecipientRepository;
    private final UserRepository userRepository;
    private final NotificationMapper notificationMapper;

    @Override
    public NotificationResponse createForUser(
            Long userId,
            String title,
            String message) {

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found: " + userId
                        ));

        Notification notification = Notification.builder()
                .title(title)
                .message(message)
                .broadcast(false)
                .build();

        notification = notificationRepository.save(notification);

        NotificationRecipient recipient =
                NotificationRecipient.builder()
                        .notification(notification)
                        .user(user)
                        .read(false)
                        .build();

        recipient =
                notificationRecipientRepository.save(recipient);

        return notificationMapper.toResponse(recipient);
    }

    @Override
    public void createBroadcast(
            String title,
            String message) {

        Notification notification = Notification.builder()
                .title(title)
                .message(message)
                .broadcast(true)
                .build();

        notificationRepository.save(notification);

        List<User> users = userRepository.findAll();

        List<NotificationRecipient> recipients =
                users.stream()
                        .map(user ->
                                NotificationRecipient.builder()
                                        .notification(notification)
                                        .user(user)
                                        .read(false)
                                        .build()
                        )
                        .toList();

        notificationRecipientRepository.saveAll(recipients);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications() {

        String username = getCurrentUsername();

        return notificationRecipientRepository
                .findMyNotifications(username)
                .stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long getMyUnreadCount() {

        String username = getCurrentUsername();

        return notificationRecipientRepository
                .countUnread(username);
    }

    @Override
    public void markAsRead(Long recipientId) {

        String username = getCurrentUsername();

        NotificationRecipient recipient =
                notificationRecipientRepository
                        .findMyNotification(
                                recipientId,
                                username
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Notification not found"
                                ));

        if (!recipient.isRead()) {
            recipient.setRead(true);
            recipient.setReadAt(
                    java.time.LocalDateTime.now()
            );

            notificationRecipientRepository.save(recipient);
        }
    }

    @Override
    public void markAllAsRead() {

        String username = getCurrentUsername();

        List<NotificationRecipient> recipients =
                notificationRecipientRepository
                        .findMyUnreadNotifications(username);

        if (recipients.isEmpty()) {
            return;
        }

        java.time.LocalDateTime readAt =
                java.time.LocalDateTime.now();

        recipients.forEach(recipient -> {
            recipient.setRead(true);
            recipient.setReadAt(readAt);
        });

        notificationRecipientRepository.saveAll(recipients);
    }

    private String getCurrentUsername() {

        return SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();
    }
}