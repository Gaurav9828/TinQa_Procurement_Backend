package com.tinqa.procurement.security.access;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.config.Authorities;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets clients show and hide what the signed-in user may do. The backends still check every request themselves.
 */
@RestController
@RequestMapping("/v1/access")
@PreAuthorize(Authorities.AUTHENTICATED)
@RequiredArgsConstructor
public class AccessController {

    private static final AccessDTOs.Catalog CATALOG = AccessDTOs.Catalog.build();

    private final CurrentUserProvider currentUserProvider;
    private final AccessResolver accessResolver;

    /** The caller's designation, groups and permissions, as of now. */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AccessDTOs.MyAccess>> myAccess() {
        UserAccess access = accessResolver.resolve(currentUserProvider.getCurrentUser());
        return ApiResponses.ok(AccessDTOs.MyAccess.of(access), "Access retrieved successfully");
    }

    /** Every designation, permission group and permission, for designation pickers and access screens. */
    @GetMapping("/catalog")
    @PreAuthorize(Authorities.ADMIN)
    public ResponseEntity<ApiResponse<AccessDTOs.Catalog>> catalog() {
        return ApiResponses.ok(CATALOG, "Access catalog retrieved successfully");
    }
}
