package com.tinqa.procurement.security.internal;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.config.Authorities;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API (authenticated by InternalApiKeyFilter): the Ecommerce service asks whether an admin token it
 * was handed still stands, and with which permissions, so signing out, a password change, disabling an account or a
 * designation change apply there too. POST, so the token never appears in a URL or access log.
 */
@RestController
@RequestMapping("/v1/internal/auth")
@PreAuthorize(Authorities.INTERNAL_SERVICE)
@RequiredArgsConstructor
public class InternalAdminTokenStatusController {

    private final AdminTokenStatusService adminTokenStatusService;

    @PostMapping("/token-status")
    public ResponseEntity<ApiResponse<AdminTokenStatusDTOs.Response>> tokenStatus(@Valid @RequestBody AdminTokenStatusDTOs.Request request) {
        return ApiResponses.ok(adminTokenStatusService.status(request.token()), "Token status");
    }
}
