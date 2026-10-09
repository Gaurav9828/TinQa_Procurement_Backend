package com.tinqa.procurement.product.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.product.dto.ProductDTOs;
import com.tinqa.procurement.product.service.ProductService;
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
@RequestMapping("/v1/admin/products")
@RequiredArgsConstructor
public class ProductAdminController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ApiResponse<ProductDTOs.Response>> createProduct(
            @Valid @RequestBody ProductDTOs.CreateRequest request,
            HttpServletRequest httpServletRequest) {

        ProductDTOs.Response responseData = productService.createProduct(request);

        ApiResponse<ProductDTOs.Response> response = ApiResponse.<ProductDTOs.Response>builder()
                .success(true)
                .message("Product created successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTOs.Response>> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody ProductDTOs.UpdateRequest request,
            HttpServletRequest httpServletRequest) {

        ProductDTOs.Response responseData = productService.updateProduct(id, request);

        ApiResponse<ProductDTOs.Response> response = ApiResponse.<ProductDTOs.Response>builder()
                .success(true)
                .message("Product updated successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTOs.Response>> getProductById(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        ProductDTOs.Response responseData = productService.getProductById(id);

        ApiResponse<ProductDTOs.Response> response = ApiResponse.<ProductDTOs.Response>builder()
                .success(true)
                .message("Product retrieved successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/product-id/{productId}")
    public ResponseEntity<ApiResponse<ProductDTOs.Response>> getProductByProductId(
            @PathVariable String productId,
            HttpServletRequest httpServletRequest) {

        ProductDTOs.Response responseData = productService.getProductByProductId(productId);

        ApiResponse<ProductDTOs.Response> response = ApiResponse.<ProductDTOs.Response>builder()
                .success(true)
                .message("Product retrieved successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/item/{itemId}")
    public ResponseEntity<ApiResponse<List<ProductDTOs.Response>>> getProductsByItemId(
            @PathVariable Long itemId,
            HttpServletRequest httpServletRequest) {

        List<ProductDTOs.Response> responseData = productService.getProductsByItemId(itemId);

        ApiResponse<List<ProductDTOs.Response>> response = ApiResponse.<List<ProductDTOs.Response>>builder()
                .success(true)
                .message("Products retrieved successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<ProductDTOs.Response>>> getProducts(
            @RequestParam(required = false) Boolean isActive,
            Pageable pageable,
            HttpServletRequest httpServletRequest) {

        Page<ProductDTOs.Response> responseData = productService.getProducts(isActive, pageable);

        ApiResponse<Page<ProductDTOs.Response>> response = ApiResponse.<Page<ProductDTOs.Response>>builder()
                .success(true)
                .message("Products retrieved successfully")
                .data(responseData)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        productService.deleteProduct(id);

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(true)
                .message("Product deleted and its stock returned successfully")
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(response);
    }
}
