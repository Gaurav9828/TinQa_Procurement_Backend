package com.tinqa.procurement.item.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.item.dto.CategoryDTOs;
import com.tinqa.procurement.item.dto.ItemDTOs;
import com.tinqa.procurement.item.service.ItemService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/admin/items")
@RequiredArgsConstructor
public class ItemAdminController {

    private final ItemService itemService;

    // --- Category Endpoints ---

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CategoryDTOs.Response>> createCategory(
            @Valid @RequestBody CategoryDTOs.CreateRequest request,
            HttpServletRequest httpServletRequest) {

        CategoryDTOs.Response responseData = itemService.createCategory(request);

        ApiResponse<CategoryDTOs.Response> response = ApiResponse.<CategoryDTOs.Response>builder()
                .success(true)
                .message("Category created successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryDTOs.Response>>> getAllCategories(
            HttpServletRequest httpServletRequest) {

        List<CategoryDTOs.Response> responseData = itemService.getAllCategories();

        ApiResponse<List<CategoryDTOs.Response>> response = ApiResponse.<List<CategoryDTOs.Response>>builder()
                .success(true)
                .message("Categories retrieved successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    // --- Item Endpoints ---

    @PostMapping
    public ResponseEntity<ApiResponse<ItemDTOs.Response>> createItem(
            @Valid @RequestBody ItemDTOs.CreateRequest request,
            HttpServletRequest httpServletRequest) {

        ItemDTOs.Response responseData = itemService.createItem(request);

        ApiResponse<ItemDTOs.Response> response = ApiResponse.<ItemDTOs.Response>builder()
                .success(true)
                .message("Item created successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ItemDTOs.Response>> updateItem(
            @PathVariable Long id,
            @Valid @RequestBody ItemDTOs.UpdateRequest request,
            HttpServletRequest httpServletRequest) {

        ItemDTOs.Response responseData = itemService.updateItem(id, request);

        ApiResponse<ItemDTOs.Response> response = ApiResponse.<ItemDTOs.Response>builder()
                .success(true)
                .message("Item updated successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ItemDTOs.Response>> getItemById(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        ItemDTOs.Response responseData = itemService.getItemById(id);

        ApiResponse<ItemDTOs.Response> response = ApiResponse.<ItemDTOs.Response>builder()
                .success(true)
                .message("Item retrieved successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<ItemDTOs.Response>>> getAllItems(
            Pageable pageable,
            HttpServletRequest httpServletRequest) {

        Page<ItemDTOs.Response> responseData = itemService.getAllItems(pageable);

        ApiResponse<Page<ItemDTOs.Response>> response = ApiResponse.<Page<ItemDTOs.Response>>builder()
                .success(true)
                .message("Items retrieved successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteItem(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        itemService.deleteItem(id);

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(true)
                .message("Item deleted successfully")
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }
}