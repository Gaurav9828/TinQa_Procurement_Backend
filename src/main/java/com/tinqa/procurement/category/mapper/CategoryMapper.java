package com.tinqa.procurement.category.mapper;

import com.tinqa.procurement.category.dto.CategoryDTOs;
import com.tinqa.procurement.category.entity.Category;
import org.springframework.stereotype.Component;

@Component
public class CategoryMapper {

    public CategoryDTOs.Response toResponse(Category category) {
        return CategoryDTOs.Response.builder()
                .id(category.getId())
                .type(category.getType())
                .name(category.getName())
                .code(category.getCode())
                .description(category.getDescription())
                .isActive(category.getIsActive())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .createdBy(category.getCreatedBy())
                .updatedBy(category.getUpdatedBy())
                .build();
    }
}
