package com.tinqa.procurement.dealer.controller;

import com.tinqa.procurement.category.dto.CategoryDTOs;
import com.tinqa.procurement.category.enums.CategoryType;
import com.tinqa.procurement.category.service.CategoryService;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.dealer.dto.DealerDTOs;
import com.tinqa.procurement.dealer.dto.DealerFilter;
import com.tinqa.procurement.dealer.service.DealerService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/v1/admin/dealers")
@RequiredArgsConstructor
public class DealerAdminController {

    private final DealerService dealerService;
    private final CategoryService categoryService;

    // --- Dealer categories ---

    // Dealers are assigned item categories (created under Item Management); this lists them for the dealer screens
    @RequiresPermission(Permission.DEALER_VIEW)
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryDTOs.Response>>> getAllCategories() {
        return ApiResponses.ok(categoryService.getCategories(CategoryType.ITEM), "Categories fetched successfully");
    }

    // --- Dealers ---

    @RequiresPermission(Permission.DEALER_CREATE)
    @PostMapping
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> createDealer(@Valid @RequestBody DealerDTOs.CreateRequest request) {
        return ApiResponses.created(dealerService.createDealer(request), "Dealer created successfully");
    }

    @RequiresPermission(Permission.DEALER_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> updateDealer(@PathVariable Long id,
                                                                         @Valid @RequestBody DealerDTOs.UpdateRequest request) {
        return ApiResponses.ok(dealerService.updateDealer(id, request), "Dealer updated successfully");
    }

    @RequiresPermission(Permission.DEALER_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> getDealerById(@PathVariable Long id) {
        return ApiResponses.ok(dealerService.getDealerById(id), "Dealer fetched successfully");
    }

    @RequiresPermission(Permission.DEALER_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<Page<DealerDTOs.Response>>> getAllDealers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<Long> categoryId,
            @RequestParam(required = false) List<String> city,
            @RequestParam(required = false) Boolean isActive,
            Pageable pageable) {
        DealerFilter filter = new DealerFilter(search, categoryId, city, isActive);
        return ApiResponses.ok(dealerService.getAllDealers(filter, pageable), "Dealers fetched successfully");
    }

    @RequiresPermission(Permission.DEALER_VIEW)
    @GetMapping("/cities")
    public ResponseEntity<ApiResponse<List<String>>> getDealerCities() {
        return ApiResponses.ok(dealerService.getDealerCities(), "Dealer cities fetched successfully");
    }

    @RequiresPermission(Permission.DEALER_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDealer(@PathVariable Long id) {
        dealerService.deleteDealer(id);
        return ApiResponses.ok("Dealer deleted successfully");
    }

    @RequiresPermission(Permission.DEALER_TOGGLE_STATUS)
    @PatchMapping("/{id}/toggle-status")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> toggleDealerStatus(@PathVariable Long id) {
        return ApiResponses.ok(dealerService.toggleDealerStatus(id), "Dealer status toggled successfully");
    }

    // --- Dealer to category mapping ---

    @RequiresPermission(Permission.DEALER_CATEGORY_MANAGE)
    @PostMapping("/{dealerId}/categories")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> assignCategories(@PathVariable Long dealerId,
                                                                             @RequestBody Set<Long> categoryIds) {
        return ApiResponses.ok(dealerService.assignCategories(dealerId, categoryIds), "Categories assigned to dealer successfully");
    }

    @RequiresPermission(Permission.DEALER_CATEGORY_MANAGE)
    @PutMapping("/{dealerId}/categories")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> updateDealerCategories(@PathVariable Long dealerId,
                                                                                   @RequestBody Set<Long> categoryIds) {
        return ApiResponses.ok(dealerService.updateDealerCategories(dealerId, categoryIds), "Dealer categories updated successfully");
    }

    @RequiresPermission(Permission.DEALER_CATEGORY_MANAGE)
    @DeleteMapping("/{dealerId}/categories/{categoryId}")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> removeCategoryFromDealer(@PathVariable Long dealerId,
                                                                                     @PathVariable Long categoryId) {
        return ApiResponses.ok(dealerService.removeCategoryFromDealer(dealerId, categoryId), "Category unmapped from dealer successfully");
    }
}
