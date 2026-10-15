package com.tinqa.procurement.security.dto;

import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

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

    private boolean isFirstLogin;

    private AuthClient authClient;

    // What the account may do; the same as GET /v1/access/me at sign-in time
    private String designation;

    private String designationLabel;

    private List<String> permissionGroups;

    private List<String> permissions;
}