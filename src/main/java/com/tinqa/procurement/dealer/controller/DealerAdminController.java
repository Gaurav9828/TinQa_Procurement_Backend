package com.tinqa.procurement.dealer.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.dealer.dto.DealerDTOs;
import com.tinqa.procurement.dealer.service.DealerService;
import com.tinqa.procurement.item.dto.CategoryDTOs;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/v1/admin/dealers")
@RequiredArgsConstructor
public class DealerAdminController {

    private final DealerService dealerService;

    // --- Category Operations ---

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CategoryDTOs.Response>> createCategory(
            @Valid @RequestBody CategoryDTOs.CreateRequest request,
            HttpServletRequest httpServletRequest) {

        CategoryDTOs.Response response = dealerService.createCategory(request);

        ApiResponse<CategoryDTOs.Response> apiResponse = ApiResponse.<CategoryDTOs.Response>builder()
                .success(true)
                .message("Category created successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(apiResponse);
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryDTOs.Response>>> getAllCategories(
            HttpServletRequest httpServletRequest) {

        List<CategoryDTOs.Response> response = dealerService.getAllCategories();

        ApiResponse<List<CategoryDTOs.Response>> apiResponse = ApiResponse.<List<CategoryDTOs.Response>>builder()
                .success(true)
                .message("Categories fetched successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    // --- Dealer Management Operations ---

    @PostMapping
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> createDealer(
            @Valid @RequestBody DealerDTOs.CreateRequest request,
            HttpServletRequest httpServletRequest) {

        DealerDTOs.Response response = dealerService.createDealer(request);

        ApiResponse<DealerDTOs.Response> apiResponse = ApiResponse.<DealerDTOs.Response>builder()
                .success(true)
                .message("Dealer created successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(apiResponse);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> updateDealer(
            @PathVariable Long id,
            @Valid @RequestBody DealerDTOs.UpdateRequest request,
            HttpServletRequest httpServletRequest) {

        DealerDTOs.Response response = dealerService.updateDealer(id, request);

        ApiResponse<DealerDTOs.Response> apiResponse = ApiResponse.<DealerDTOs.Response>builder()
                .success(true)
                .message("Dealer updated successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> getDealerById(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        DealerDTOs.Response response = dealerService.getDealerById(id);

        ApiResponse<DealerDTOs.Response> apiResponse = ApiResponse.<DealerDTOs.Response>builder()
                .success(true)
                .message("Dealer fetched successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<DealerDTOs.Response>>> getAllDealers(
            Pageable pageable,
            HttpServletRequest httpServletRequest) {

        Page<DealerDTOs.Response> response = dealerService.getAllDealers(pageable);

        ApiResponse<Page<DealerDTOs.Response>> apiResponse = ApiResponse.<Page<DealerDTOs.Response>>builder()
                .success(true)
                .message("Dealers fetched successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDealer(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        dealerService.deleteDealer(id);

        ApiResponse<Void> apiResponse = ApiResponse.<Void>builder()
                .success(true)
                .message("Dealer deleted successfully")
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PatchMapping("/{id}/toggle-status")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> toggleDealerStatus(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        DealerDTOs.Response response = dealerService.toggleDealerStatus(id);

        ApiResponse<DealerDTOs.Response> apiResponse = ApiResponse.<DealerDTOs.Response>builder()
                .success(true)
                .message("Dealer status toggled successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    // --- Dealer Category Mapping Operations ---

    @PostMapping("/{dealerId}/categories")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> assignCategories(
            @PathVariable Long dealerId,
            @RequestBody Set<Long> categoryIds,
            HttpServletRequest httpServletRequest) {

        DealerDTOs.Response response = dealerService.assignCategories(dealerId, categoryIds);

        ApiResponse<DealerDTOs.Response> apiResponse = ApiResponse.<DealerDTOs.Response>builder()
                .success(true)
                .message("Categories assigned to dealer successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PutMapping("/{dealerId}/categories")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> updateDealerCategories(
            @PathVariable Long dealerId,
            @RequestBody Set<Long> categoryIds,
            HttpServletRequest httpServletRequest) {

        DealerDTOs.Response response = dealerService.updateDealerCategories(dealerId, categoryIds);

        ApiResponse<DealerDTOs.Response> apiResponse = ApiResponse.<DealerDTOs.Response>builder()
                .success(true)
                .message("Dealer categories updated successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @DeleteMapping("/{dealerId}/categories/{categoryId}")
    public ResponseEntity<ApiResponse<DealerDTOs.Response>> removeCategoryFromDealer(
            @PathVariable Long dealerId,
            @PathVariable Long categoryId,
            HttpServletRequest httpServletRequest) {

        DealerDTOs.Response response = dealerService.removeCategoryFromDealer(dealerId, categoryId);

        ApiResponse<DealerDTOs.Response> apiResponse = ApiResponse.<DealerDTOs.Response>builder()
                .success(true)
                .message("Category unmapped from dealer successfully")
                .data(response)
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }
}