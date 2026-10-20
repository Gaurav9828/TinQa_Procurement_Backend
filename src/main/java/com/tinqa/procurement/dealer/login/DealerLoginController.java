package com.tinqa.procurement.dealer.login;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// A dealer's web login: created by a Dealer Manager, approved by someone else, then switched on/off or reset
@RestController
@RequiredArgsConstructor
public class DealerLoginController {

    private static final String BASE = "/v1/admin/dealers/{dealerId}/login";

    private final DealerLoginService dealerLoginService;

    // data is null when the dealer has no login yet
    @RequiresPermission(Permission.DEALER_VIEW)
    @GetMapping(BASE)
    public ResponseEntity<ApiResponse<DealerLoginDTOs.Response>> getLogin(@PathVariable Long dealerId) {
        return ApiResponses.ok(dealerLoginService.getLogin(dealerId).orElse(null), "Dealer login fetched successfully");
    }

    @RequiresPermission(Permission.DEALER_LOGIN_MANAGE)
    @PostMapping(BASE)
    public ResponseEntity<ApiResponse<DealerLoginDTOs.Response>> createLogin(@PathVariable Long dealerId,
                                                                             @Valid @RequestBody DealerLoginDTOs.CreateRequest request) {
        return ApiResponses.created(dealerLoginService.createLogin(dealerId, request),
                "Dealer login created. It can sign in once someone else approves it.");
    }

    @RequiresPermission(Permission.DEALER_LOGIN_APPROVE)
    @PatchMapping(BASE + "/approval")
    public ResponseEntity<ApiResponse<DealerLoginDTOs.Response>> decide(@PathVariable Long dealerId,
                                                                        @Valid @RequestBody DealerLoginDTOs.DecisionRequest request) {
        DealerLoginDTOs.Response response = dealerLoginService.decide(dealerId, request);
        return ApiResponses.ok(response, response.getStatus() == DealerLoginStatus.ACTIVE
                ? "Dealer login approved" : "Dealer login rejected");
    }

    @RequiresPermission(Permission.DEALER_LOGIN_MANAGE)
    @PatchMapping(BASE + "/status")
    public ResponseEntity<ApiResponse<DealerLoginDTOs.Response>> setStatus(@PathVariable Long dealerId,
                                                                           @Valid @RequestBody DealerLoginDTOs.StatusRequest request) {
        boolean enabled = request.getEnabled();
        return ApiResponses.ok(dealerLoginService.setEnabled(dealerId, enabled),
                enabled ? "Dealer login enabled" : "Dealer login disabled");
    }

    @RequiresPermission(Permission.DEALER_LOGIN_MANAGE)
    @PostMapping(BASE + "/reset-password")
    public ResponseEntity<ApiResponse<DealerLoginDTOs.Response>> resetPassword(@PathVariable Long dealerId) {
        return ApiResponses.ok(dealerLoginService.resetPassword(dealerId),
                "Password reset to the default. The dealer must change it at their next sign-in.");
    }

    // The approval queue (status=APPROVAL_PENDING), or every login
    @RequiresPermission(Permission.DEALER_LOGIN_APPROVE)
    @GetMapping("/v1/admin/dealer-logins")
    public ResponseEntity<ApiResponse<List<DealerLoginDTOs.Response>>> getLogins(
            @RequestParam(required = false) DealerLoginStatus status) {
        return ApiResponses.ok(dealerLoginService.getLogins(status), "Dealer logins fetched successfully");
    }
}
