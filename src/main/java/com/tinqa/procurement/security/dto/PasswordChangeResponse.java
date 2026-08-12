package com.tinqa.procurement.security.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PasswordChangeResponse {

    private boolean success;

    private String message;

    private String code;

    private boolean requiresLogin;
}