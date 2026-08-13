package com.tinqa.procurement.notification.service;

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

    void markAsRead(Long recipientId);

    void markAllAsRead();
}