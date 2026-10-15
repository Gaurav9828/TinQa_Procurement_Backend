package com.tinqa.procurement.notification.service;

import com.tinqa.procurement.notification.dto.NotificationResponse;
import com.tinqa.procurement.security.access.Permission;

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
     * Notifies every enabled user who holds the permission (typically the approvers of something), except
     * excludeUserId (usually the person who acted).
     */
    void createForPermission(Permission permission, Long excludeUserId, String title, String message);

    void markAsRead(Long recipientId);

    void markAllAsRead();
}