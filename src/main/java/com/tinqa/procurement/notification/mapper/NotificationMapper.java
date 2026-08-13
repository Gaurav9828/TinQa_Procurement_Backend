package com.tinqa.procurement.notification.mapper;

import com.tinqa.procurement.notification.dto.NotificationResponse;
import com.tinqa.procurement.notification.entity.NotificationRecipient;
import org.springframework.stereotype.Component;

@Component
public class NotificationMapper {

    public NotificationResponse toResponse(
            NotificationRecipient recipient) {

        return NotificationResponse.builder()
                .id(recipient.getId())
                .title(recipient.getNotification().getTitle())
                .message(recipient.getNotification().getMessage())
                .read(recipient.isRead())
                .broadcast(
                        recipient.getNotification().isBroadcast()
                )
                .createdAt(
                        recipient.getNotification().getCreatedAt()
                )
                .build();
    }
}