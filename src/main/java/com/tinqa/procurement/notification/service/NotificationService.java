package com.tinqa.procurement.notification.service;

import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.notification.dto.NotificationResponse;

import java.util.List;

public interface NotificationService {

    NotificationResponse createForUser(
            Long userId,
            String title,
            String message
    );

    void createBroadcast(
            String title,
            String message
    );

    List<NotificationResponse> getMyNotifications();

    long getMyUnreadCount();

    /**
     * Notifies every enabled user with the role, except excludeUserId (usually the person who acted).
     */
    void createForRole(Role role, Long excludeUserId, String title, String message);

    void markAsRead(Long recipientId);

    void markAllAsRead();
}