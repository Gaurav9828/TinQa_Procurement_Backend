package com.tinqa.procurement.notification.controller;

import com.tinqa.procurement.response.ApiResponse;
import com.tinqa.procurement.notification.dto.NotificationResponse;
import com.tinqa.procurement.notification.dto.UnreadCountResponse;
import com.tinqa.procurement.notification.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>>
    getMyNotifications(
            HttpServletRequest httpRequest) {

        List<NotificationResponse> notifications =
                notificationService.getMyNotifications();

        return ResponseEntity.ok(
                ApiResponse.<List<NotificationResponse>>builder()
                        .success(true)
                        .message("Notifications Retrieved Successfully")
                        .data(notifications)
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>>
    getUnreadCount(
            HttpServletRequest httpRequest) {

        long unreadCount =
                notificationService.getMyUnreadCount();

        UnreadCountResponse response =
                UnreadCountResponse.builder()
                        .unreadCount(unreadCount)
                        .build();

        return ResponseEntity.ok(
                ApiResponse.<UnreadCountResponse>builder()
                        .success(true)
                        .message("Unread Notification Count Retrieved Successfully")
                        .data(response)
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<Void>>
    markAsRead(
            @PathVariable Long notificationId,
            HttpServletRequest httpRequest) {

        notificationService.markAsRead(notificationId);

        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .success(true)
                        .message("Notification Marked As Read")
                        .data(null)
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>>
    markAllAsRead(
            HttpServletRequest httpRequest) {

        notificationService.markAllAsRead();

        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .success(true)
                        .message("All Notifications Marked As Read")
                        .data(null)
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }
}