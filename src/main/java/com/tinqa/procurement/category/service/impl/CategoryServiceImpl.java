package com.tinqa.procurement.category.service.impl;

import com.tinqa.procurement.category.dto.CategoryDTOs;
import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.category.enums.CategoryType;
import com.tinqa.procurement.category.mapper.CategoryMapper;
import com.tinqa.procurement.category.repository.CategoryRepository;
import com.tinqa.procurement.category.service.CategoryService;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public CategoryDTOs.Response createCategory(CategoryType type, CategoryDTOs.CreateRequest request) {
        // Codes are unique per category type (same rule as the database constraint)
        if (categoryRepository.existsByTypeAndCode(type, request.getCode())) {
            throw new BadRequestException("Category code already exists: " + request.getCode());
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();

        Category category = Category.builder()
                .type(type)
                .name(request.getName())
                .code(request.getCode())
                .description(request.getDescription())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();
        return categoryMapper.toResponse(categoryRepository.save(category));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryDTOs.Response> getCategories(CategoryType type) {
        return categoryRepository.findByType(type).stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> getCategories(Collection<Long> ids, CategoryType type) {
        List<Category> categories = categoryRepository.findByIdInAndType(ids, type);
        if (categories.size() != ids.size()) {
            throw new ResourceNotFoundException("One or more Category IDs are invalid");
        }
        return categories;
    }
}
