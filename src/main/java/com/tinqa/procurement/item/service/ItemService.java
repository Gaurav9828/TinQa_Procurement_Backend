package com.tinqa.procurement.item.service;

import com.tinqa.procurement.item.dto.ItemDTOs;
import com.tinqa.procurement.item.dto.ItemFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Items and their warranties. Item categories are managed by
 * {@link com.tinqa.procurement.category.service.CategoryService}.
 */
public interface ItemService {

    ItemDTOs.Response createItem(ItemDTOs.CreateRequest request);

    ItemDTOs.Response updateItem(Long id, ItemDTOs.UpdateRequest request);

    ItemDTOs.Response getItemById(Long id);

    List<ItemDTOs.WarrantyResponse> getItemWarranties(Long itemId);

    Page<ItemDTOs.Response> getAllItems(ItemFilter filter, Pageable pageable);

    /** Soft delete: the item is deactivated. */
    void deleteItem(Long id);
}
