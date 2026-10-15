package com.tinqa.procurement.product.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.product.dto.ProductDTOs;
import com.tinqa.procurement.product.service.ProductService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/products")
@RequiredArgsConstructor
public class ProductAdminController {

    private static final String PRODUCT_RETRIEVED = "Product retrieved successfully";

    private final ProductService productService;

    @RequiresPermission(Permission.PRODUCT_CREATE)
    @PostMapping
    public ResponseEntity<ApiResponse<ProductDTOs.Response>> createProduct(@Valid @RequestBody ProductDTOs.CreateRequest request) {
        return ApiResponses.created(productService.createProduct(request), "Product created successfully");
    }

    @RequiresPermission(Permission.PRODUCT_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTOs.Response>> updateProduct(@PathVariable Long id,
                                                                           @Valid @RequestBody ProductDTOs.UpdateRequest request) {
        return ApiResponses.ok(productService.updateProduct(id, request), "Product updated successfully");
    }

    @RequiresPermission(Permission.PRODUCT_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTOs.Response>> getProductById(@PathVariable Long id) {
        return ApiResponses.ok(productService.getProductById(id), PRODUCT_RETRIEVED);
    }

    @RequiresPermission(Permission.PRODUCT_VIEW)
    @GetMapping("/product-id/{productId}")
    public ResponseEntity<ApiResponse<ProductDTOs.Response>> getProductByProductId(@PathVariable String productId) {
        return ApiResponses.ok(productService.getProductByProductId(productId), PRODUCT_RETRIEVED);
    }

    @RequiresPermission(Permission.PRODUCT_VIEW)
    @GetMapping("/item/{itemId}")
    public ResponseEntity<ApiResponse<List<ProductDTOs.Response>>> getProductsByItemId(@PathVariable Long itemId) {
        return ApiResponses.ok(productService.getProductsByItemId(itemId), "Products retrieved successfully");
    }

    @RequiresPermission(Permission.PRODUCT_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ProductDTOs.Response>>> getProducts(@RequestParam(required = false) Boolean isActive,
                                                                             Pageable pageable) {
        return ApiResponses.ok(productService.getProducts(isActive, pageable), "Products retrieved successfully");
    }

    @RequiresPermission(Permission.PRODUCT_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ApiResponses.ok("Product deleted and its stock returned successfully");
    }
}
