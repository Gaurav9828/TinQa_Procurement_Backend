package com.tinqa.procurement.security.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PasswordChangeResponse {

    private boolean success;

    private String message;

    private String code;

    private boolean requiresLogin;
}