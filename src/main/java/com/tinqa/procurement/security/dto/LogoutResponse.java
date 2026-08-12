package com.tinqa.procurement.security.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LogoutResponse {

    private boolean success;
    private String message;
}