package com.tinqa.procurement.customer;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Customer care asks for a change to a storefront customer's details. Nothing is stored: everyone who may edit
 * customers (CUSTOMER_UPDATE, the Super Admin) is notified with the requested values and a link to the customer,
 * and makes the change there after checking it.
 */
@RestController
@RequestMapping("/v1/admin/customer-change-requests")
@RequiredArgsConstructor
public class CustomerChangeRequestController {

    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;

    @RequiresPermission(Permission.CUSTOMER_CHANGE_REQUEST)
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> request(@Valid @RequestBody CustomerChangeRequest request) {
        User requester = currentUserProvider.getCurrentUser();
        String changes = request.getChanges().entrySet().stream()
                .map(CustomerChangeRequestController::describe)
                .collect(Collectors.joining("; "));
        notificationService.createForPermission(Permission.CUSTOMER_UPDATE, requester.getId(),
                "Customer detail change requested",
                requester.getUsername() + " asks to change customer " + request.getCustomerName().trim()
                        + " (#" + request.getCustomerId() + "): " + changes + ". Reason: " + request.getReason().trim(),
                NotificationLinks.CUSTOMERS + "/" + request.getCustomerId());
        return ApiResponses.ok("Change requested. A Super Admin has been notified.");
    }

    private static String describe(Map.Entry<CustomerChangeRequest.CustomerField, String> change) {
        String value = change.getValue() == null || change.getValue().isBlank() ? "(remove)" : change.getValue().trim();
        return change.getKey().label() + " -> " + value;
    }
}
