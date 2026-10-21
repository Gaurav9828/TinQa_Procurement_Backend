package com.tinqa.procurement.security.password.dto;

import lombok.Getter;
import lombok.Setter;

/** The token from a password link (sent in the body, so it stays out of server logs). */
@Getter
@Setter
public class PasswordLinkCheckRequest {
    private String token;
}
