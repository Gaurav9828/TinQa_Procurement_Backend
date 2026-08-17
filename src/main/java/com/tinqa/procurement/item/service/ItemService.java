package com.tinqa.procurement.item.service;

import com.tinqa.procurement.common.dto.CategoryDTOs;
import com.tinqa.procurement.item.dto.ItemDTOs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ItemService {
    // Categories
    CategoryDTOs.Response createCategory(CategoryDTOs.CreateRequest request);
    List<CategoryDTOs.Response> getAllCategories();

    // Items
    ItemDTOs.Response createItem(ItemDTOs.CreateRequest request);
    ItemDTOs.Response updateItem(Long id, ItemDTOs.UpdateRequest request);
    ItemDTOs.Response getItemById(Long id);
    Page<ItemDTOs.Response> getAllItems(Pageable pageable);
    void deleteItem(Long id);
}