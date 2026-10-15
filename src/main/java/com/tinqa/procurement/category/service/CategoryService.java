package com.tinqa.procurement.category.service;

import com.tinqa.procurement.category.dto.CategoryDTOs;
import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.category.enums.CategoryType;

import java.util.Collection;
import java.util.List;

public interface CategoryService {

    CategoryDTOs.Response createCategory(CategoryType type, CategoryDTOs.CreateRequest request);

    List<CategoryDTOs.Response> getCategories(CategoryType type);

    /** The category with this id and type; 404 if there is none (a category of another type counts as none). */
    Category getCategory(Long id, CategoryType type);

    /** Every requested category, all of the given type; 404 if any id is unknown or of another type. */
    List<Category> getCategories(Collection<Long> ids, CategoryType type);
}
