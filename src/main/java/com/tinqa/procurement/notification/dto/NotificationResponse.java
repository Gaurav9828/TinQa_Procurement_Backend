package com.tinqa.procurement.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class NotificationResponse {

    private Long id;

    private String title;

    private String message;

    private boolean read;

    private boolean broadcast;

    private LocalDateTime createdAt;
}