package com.tinqa.procurement.notification.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UnreadCountResponse {

    private long unreadCount;

    /** Completed meetings the caller still owes feedback on (the menu badge); 0 without MEETING_VIEW_OWN. */
    private long meetingFeedbackPending;
}