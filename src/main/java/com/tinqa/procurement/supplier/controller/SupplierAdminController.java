package com.tinqa.procurement.supplier.controller;

import com.tinqa.procurement.category.dto.CategoryDTOs;
import com.tinqa.procurement.category.enums.CategoryType;
import com.tinqa.procurement.category.service.CategoryService;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.supplier.dto.SupplierDTOs;
import com.tinqa.procurement.supplier.dto.SupplierFilter;
import com.tinqa.procurement.supplier.service.SupplierService;
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
@RequestMapping("/v1/admin/suppliers")
@RequiredArgsConstructor
public class SupplierAdminController {

    private final SupplierService supplierService;
    private final CategoryService categoryService;

    // --- Supplier categories ---

    // Suppliers are assigned item categories (created under Item Management); this lists them for the supplier screens
    @RequiresPermission(Permission.SUPPLIER_VIEW)
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryDTOs.Response>>> getAllCategories() {
        return ApiResponses.ok(categoryService.getCategories(CategoryType.ITEM), "Categories fetched successfully");
    }

    // --- Suppliers ---

    @RequiresPermission(Permission.SUPPLIER_CREATE)
    @PostMapping
    public ResponseEntity<ApiResponse<SupplierDTOs.Response>> createSupplier(@Valid @RequestBody SupplierDTOs.CreateRequest request) {
        return ApiResponses.created(supplierService.createSupplier(request), "Supplier created successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SupplierDTOs.Response>> updateSupplier(@PathVariable Long id,
                                                                         @Valid @RequestBody SupplierDTOs.UpdateRequest request) {
        return ApiResponses.ok(supplierService.updateSupplier(id, request), "Supplier updated successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SupplierDTOs.Response>> getSupplierById(@PathVariable Long id) {
        return ApiResponses.ok(supplierService.getSupplierById(id), "Supplier fetched successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<Page<SupplierDTOs.Response>>> getAllSuppliers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<Long> categoryId,
            @RequestParam(required = false) List<String> city,
            @RequestParam(required = false) Boolean isActive,
            Pageable pageable) {
        SupplierFilter filter = new SupplierFilter(search, categoryId, city, isActive);
        return ApiResponses.ok(supplierService.getAllSuppliers(filter, pageable), "Suppliers fetched successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_VIEW)
    @GetMapping("/cities")
    public ResponseEntity<ApiResponse<List<String>>> getSupplierCities() {
        return ApiResponses.ok(supplierService.getSupplierCities(), "Supplier cities fetched successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSupplier(@PathVariable Long id) {
        supplierService.deleteSupplier(id);
        return ApiResponses.ok("Supplier deleted successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_TOGGLE_STATUS)
    @PatchMapping("/{id}/toggle-status")
    public ResponseEntity<ApiResponse<SupplierDTOs.Response>> toggleSupplierStatus(@PathVariable Long id) {
        return ApiResponses.ok(supplierService.toggleSupplierStatus(id), "Supplier status toggled successfully");
    }

    // --- Supplier to category mapping ---

    @RequiresPermission(Permission.SUPPLIER_CATEGORY_MANAGE)
    @PostMapping("/{supplierId}/categories")
    public ResponseEntity<ApiResponse<SupplierDTOs.Response>> assignCategories(@PathVariable Long supplierId,
                                                                             @RequestBody Set<Long> categoryIds) {
        return ApiResponses.ok(supplierService.assignCategories(supplierId, categoryIds), "Categories assigned to supplier successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_CATEGORY_MANAGE)
    @PutMapping("/{supplierId}/categories")
    public ResponseEntity<ApiResponse<SupplierDTOs.Response>> updateSupplierCategories(@PathVariable Long supplierId,
                                                                                   @RequestBody Set<Long> categoryIds) {
        return ApiResponses.ok(supplierService.updateSupplierCategories(supplierId, categoryIds), "Supplier categories updated successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_CATEGORY_MANAGE)
    @DeleteMapping("/{supplierId}/categories/{categoryId}")
    public ResponseEntity<ApiResponse<SupplierDTOs.Response>> removeCategoryFromSupplier(@PathVariable Long supplierId,
                                                                                     @PathVariable Long categoryId) {
        return ApiResponses.ok(supplierService.removeCategoryFromSupplier(supplierId, categoryId), "Category unmapped from supplier successfully");
    }
}
