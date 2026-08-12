package com.tinqa.procurement.security.dto;

import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {

    private String accessToken;

    private String tokenType;

    private long expiresIn;

    private Long userId;

    private String username;

    private String email;

    private Role role;

    private AuthClient authClient;
}