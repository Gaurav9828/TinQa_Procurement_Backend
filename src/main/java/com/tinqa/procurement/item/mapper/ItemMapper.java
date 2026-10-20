package com.tinqa.procurement.item.mapper;

import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.item.dto.ItemDTOs;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.entity.ItemWarranty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class ItemMapper {

    public ItemDTOs.Response toResponse(Item item) {
        ItemDTOs.Response response = new ItemDTOs.Response();
        response.setId(item.getId());
        response.setCategories(item.getCategories().stream()
                .sorted(Comparator.comparing(Category::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toCategorySummary)
                .toList());
        response.setName(item.getName());
        response.setBrand(item.getBrand());
        response.setSku(item.getSku());
        response.setUnitOfMeasure(item.getUnitOfMeasure());
        response.setMrp(item.getMrp());
        response.setCountryOfOrigin(item.getCountryOfOrigin());
        response.setRawMaterialsUsed(item.getRawMaterialsUsed());
        response.setTermsAndCondition(item.getTermsAndCondition());
        response.setDescription(item.getDescription());
        response.setAttributes(item.getAttributes());
        response.setWarranties(toWarrantyResponses(item.getWarranties()));
        response.setIsActive(item.getIsActive());
        response.setCreatedAt(item.getCreatedAt());
        response.setUpdatedAt(item.getUpdatedAt());
        response.setCreatedBy(item.getCreatedBy());
        response.setUpdatedBy(item.getUpdatedBy());
        return response;
    }

    private ItemDTOs.CategorySummary toCategorySummary(Category category) {
        ItemDTOs.CategorySummary summary = new ItemDTOs.CategorySummary();
        summary.setId(category.getId());
        summary.setName(category.getName());
        summary.setCode(category.getCode());
        return summary;
    }

    public List<ItemDTOs.WarrantyResponse> toWarrantyResponses(List<ItemWarranty> warranties) {
        return warranties.stream().map(this::toWarrantyResponse).toList();
    }

    public ItemDTOs.WarrantyResponse toWarrantyResponse(ItemWarranty warranty) {
        ItemDTOs.WarrantyResponse response = new ItemDTOs.WarrantyResponse();
        response.setId(warranty.getId());
        response.setItemId(warranty.getItem().getId());
        response.setWarrantyType(warranty.getWarrantyType());
        response.setTitle(warranty.getTitle());
        response.setDurationValue(warranty.getDurationValue());
        response.setDurationUnit(warranty.getDurationUnit());
        response.setProvider(warranty.getProvider());
        response.setCoverage(warranty.getCoverage());
        response.setExclusions(warranty.getExclusions());
        response.setTermsAndConditions(warranty.getTermsAndConditions());
        response.setIsActive(warranty.getIsActive());
        response.setCreatedAt(warranty.getCreatedAt());
        response.setUpdatedAt(warranty.getUpdatedAt());
        response.setCreatedBy(warranty.getCreatedBy());
        response.setUpdatedBy(warranty.getUpdatedBy());
        return response;
    }
}
