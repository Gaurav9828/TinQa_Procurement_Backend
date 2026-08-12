package com.tinqa.procurement.security.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthenticationResponse {

    private final String accessToken;
    private final String tokenType;
    private final long expiresIn;
}