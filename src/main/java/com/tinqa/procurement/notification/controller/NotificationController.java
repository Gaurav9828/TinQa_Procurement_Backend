package com.tinqa.procurement.notification.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.notification.dto.NotificationResponse;
import com.tinqa.procurement.notification.dto.UnreadCountResponse;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.config.Authorities;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Every user reads and updates only their own notifications
@RestController
@RequestMapping("/v1/notifications")
@PreAuthorize(Authorities.AUTHENTICATED)
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getMyNotifications() {
        return ApiResponses.ok(notificationService.getMyNotifications(), "Notifications Retrieved Successfully");
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount() {
        UnreadCountResponse response = UnreadCountResponse.builder()
                .unreadCount(notificationService.getMyUnreadCount())
                .build();
        return ApiResponses.ok(response, "Unread Notification Count Retrieved Successfully");
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(@PathVariable Long notificationId) {
        notificationService.markAsRead(notificationId);
        return ApiResponses.ok("Notification Marked As Read");
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead() {
        notificationService.markAllAsRead();
        return ApiResponses.ok("All Notifications Marked As Read");
    }
}
