package com.tinqa.procurement.supplier.login;

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

// A supplier's web login: created by a Supplier Manager, approved by someone else, then switched on/off or reset
@RestController
@RequiredArgsConstructor
public class SupplierLoginController {

    private static final String BASE = "/v1/admin/suppliers/{supplierId}/login";

    private final SupplierLoginService supplierLoginService;

    // data is null when the supplier has no login yet
    @RequiresPermission(Permission.SUPPLIER_VIEW)
    @GetMapping(BASE)
    public ResponseEntity<ApiResponse<SupplierLoginDTOs.Response>> getLogin(@PathVariable Long supplierId) {
        return ApiResponses.ok(supplierLoginService.getLogin(supplierId).orElse(null), "Supplier login fetched successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_LOGIN_MANAGE)
    @PostMapping(BASE)
    public ResponseEntity<ApiResponse<SupplierLoginDTOs.Response>> createLogin(@PathVariable Long supplierId,
                                                                             @Valid @RequestBody SupplierLoginDTOs.CreateRequest request) {
        return ApiResponses.created(supplierLoginService.createLogin(supplierId, request),
                "Supplier login created. It can sign in once someone else approves it.");
    }

    @RequiresPermission(Permission.SUPPLIER_LOGIN_APPROVE)
    @PatchMapping(BASE + "/approval")
    public ResponseEntity<ApiResponse<SupplierLoginDTOs.Response>> decide(@PathVariable Long supplierId,
                                                                        @Valid @RequestBody SupplierLoginDTOs.DecisionRequest request) {
        SupplierLoginDTOs.Response response = supplierLoginService.decide(supplierId, request);
        return ApiResponses.ok(response, response.getStatus() == SupplierLoginStatus.ACTIVE
                ? "Supplier login approved" : "Supplier login rejected");
    }

    @RequiresPermission(Permission.SUPPLIER_LOGIN_MANAGE)
    @PatchMapping(BASE + "/status")
    public ResponseEntity<ApiResponse<SupplierLoginDTOs.Response>> setStatus(@PathVariable Long supplierId,
                                                                           @Valid @RequestBody SupplierLoginDTOs.StatusRequest request) {
        boolean enabled = request.getEnabled();
        return ApiResponses.ok(supplierLoginService.setEnabled(supplierId, enabled),
                enabled ? "Supplier login enabled" : "Supplier login disabled");
    }

    @RequiresPermission(Permission.SUPPLIER_LOGIN_MANAGE)
    @PostMapping(BASE + "/reset-password")
    public ResponseEntity<ApiResponse<SupplierLoginDTOs.Response>> resetPassword(@PathVariable Long supplierId) {
        return ApiResponses.ok(supplierLoginService.resetPassword(supplierId),
                "Password reset to the default. The supplier must change it at their next sign-in.");
    }

    // The approval queue (status=APPROVAL_PENDING), or every login
    @RequiresPermission(Permission.SUPPLIER_LOGIN_APPROVE)
    @GetMapping("/v1/admin/supplier-logins")
    public ResponseEntity<ApiResponse<List<SupplierLoginDTOs.Response>>> getLogins(
            @RequestParam(required = false) SupplierLoginStatus status) {
        return ApiResponses.ok(supplierLoginService.getLogins(status), "Supplier logins fetched successfully");
    }
}
