package com.tinqa.procurement.item.controller;

import com.tinqa.procurement.category.dto.CategoryDTOs;
import com.tinqa.procurement.category.enums.CategoryType;
import com.tinqa.procurement.category.service.CategoryService;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.item.dto.ItemDTOs;
import com.tinqa.procurement.item.service.ItemService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/items")
@RequiredArgsConstructor
public class ItemAdminController {

    private final ItemService itemService;
    private final CategoryService categoryService;

    // --- Item categories ---

    @RequiresPermission(Permission.ITEM_CATEGORY_MANAGE)
    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CategoryDTOs.Response>> createCategory(@Valid @RequestBody CategoryDTOs.CreateRequest request) {
        return ApiResponses.created(categoryService.createCategory(CategoryType.ITEM, request), "Category created successfully");
    }

    @RequiresPermission(Permission.ITEM_VIEW)
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryDTOs.Response>>> getAllCategories() {
        return ApiResponses.ok(categoryService.getCategories(CategoryType.ITEM), "Categories retrieved successfully");
    }

    // --- Items ---

    @RequiresPermission(Permission.ITEM_CREATE)
    @PostMapping
    public ResponseEntity<ApiResponse<ItemDTOs.Response>> createItem(@Valid @RequestBody ItemDTOs.CreateRequest request) {
        return ApiResponses.created(itemService.createItem(request), "Item created successfully");
    }

    @RequiresPermission(Permission.ITEM_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ItemDTOs.Response>> updateItem(@PathVariable Long id,
                                                                     @Valid @RequestBody ItemDTOs.UpdateRequest request) {
        return ApiResponses.ok(itemService.updateItem(id, request), "Item updated successfully");
    }

    @RequiresPermission(Permission.ITEM_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ItemDTOs.Response>> getItemById(@PathVariable Long id) {
        return ApiResponses.ok(itemService.getItemById(id), "Item retrieved successfully");
    }

    @RequiresPermission(Permission.ITEM_VIEW)
    @GetMapping("/{id}/warranties")
    public ResponseEntity<ApiResponse<List<ItemDTOs.WarrantyResponse>>> getItemWarranties(@PathVariable Long id) {
        return ApiResponses.ok(itemService.getItemWarranties(id), "Item warranties retrieved successfully");
    }

    @RequiresPermission(Permission.ITEM_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ItemDTOs.Response>>> getAllItems(Pageable pageable) {
        return ApiResponses.ok(itemService.getAllItems(pageable), "Items retrieved successfully");
    }

    @RequiresPermission(Permission.ITEM_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteItem(@PathVariable Long id) {
        itemService.deleteItem(id);
        return ApiResponses.ok("Item deleted successfully");
    }
}
