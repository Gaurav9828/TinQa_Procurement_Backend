package com.tinqa.procurement.notification.service.impl;

import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.notification.dto.NotificationResponse;
import com.tinqa.procurement.notification.entity.Notification;
import com.tinqa.procurement.notification.entity.NotificationRecipient;
import com.tinqa.procurement.notification.mapper.NotificationMapper;
import com.tinqa.procurement.notification.repository.NotificationRecipientRepository;
import com.tinqa.procurement.notification.repository.NotificationRepository;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.AccessResolver;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.UserAccess;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository notificationRecipientRepository;
    private final UserRepository userRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentUserProvider currentUserProvider;
    private final AccessResolver accessResolver;
    private final Clock clock;

    @Override
    public NotificationResponse createForUser(
            Long userId,
            String title,
            String message,
            String link) {

        if (isBlank(title, message)) {
            log.warn("Skipped notification with empty title or message for user {}", userId);
            return null;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found: " + userId
                        ));

        Notification notification = Notification.builder()
                .title(title)
                .message(message)
                .link(link)
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
    public void createForPermission(Permission permission, Long excludeUserId, String title, String message, String link) {
        // Only employees (admin accounts) hold internal permissions; their access is resolved in one query, not one each
        List<User> admins = Stream.of(Role.ADMIN_L2, Role.ADMIN_L1)
                .flatMap(role -> userRepository.findByRoleAndEnabledTrue(role).stream())
                .filter(user -> !user.getId().equals(excludeUserId))
                .toList();
        Map<Long, UserAccess> access = accessResolver.resolveAll(admins);
        admins.stream()
                .filter(user -> access.get(user.getId()).has(permission))
                .forEach(user -> createForUser(user.getId(), title, message, link));
    }

    @Override
    public void createBroadcast(
            String title,
            String message) {

        if (isBlank(title, message)) {
            log.warn("Skipped broadcast notification with empty title or message");
            return;
        }

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

        String username = currentUserProvider.getCurrentUsername();

        return notificationRecipientRepository
                .findMyNotifications(username)
                .stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long getMyUnreadCount() {

        String username = currentUserProvider.getCurrentUsername();

        return notificationRecipientRepository
                .countUnread(username);
    }

    @Override
    public void markAsRead(Long recipientId) {

        String username = currentUserProvider.getCurrentUsername();

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
                    LocalDateTime.now(clock)
            );

            notificationRecipientRepository.save(recipient);
        }
    }

    @Override
    public void markAllAsRead() {

        String username = currentUserProvider.getCurrentUsername();

        List<NotificationRecipient> recipients =
                notificationRecipientRepository
                        .findMyUnreadNotifications(username);

        if (recipients.isEmpty()) {
            return;
        }

        LocalDateTime readAt = LocalDateTime.now(clock);

        recipients.forEach(recipient -> {
            recipient.setRead(true);
            recipient.setReadAt(readAt);
        });

        notificationRecipientRepository.saveAll(recipients);
    }

    // A notification without text is useless to the recipient; it means the caller had nothing to say
    private boolean isBlank(String title, String message) {
        return title == null || title.isBlank() || message == null || message.isBlank();
    }
}
