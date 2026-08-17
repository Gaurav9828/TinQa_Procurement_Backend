package com.tinqa.procurement.item.service.impl;

import com.tinqa.procurement.common.entity.Category;
import com.tinqa.procurement.common.enums.CategoryType;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.dto.CategoryDTOs;
import com.tinqa.procurement.item.dto.ItemDTOs;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.common.entity.Category;
import com.tinqa.procurement.common.repository.CategoryRepository;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.item.service.ItemService;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public CategoryDTOs.Response createCategory(CategoryDTOs.CreateRequest request) {
        if (categoryRepository.existsByCode(request.getCode())) {
            throw new BadRequestException("Category code already exists: " + request.getCode());
        }
        Long currentUserId = currentUserProvider.getCurrentUser().getId();

        Category category = Category.builder()
                .name(request.getName())
                .type(CategoryType.ITEM)
                .code(request.getCode())
                .description(request.getDescription())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        return mapToCategoryResponse(categoryRepository.save(category));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryDTOs.Response> getAllCategories() {
        return categoryRepository.findByType(CategoryType.ITEM).stream()
                .map(this::mapToCategoryResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ItemDTOs.Response createItem(ItemDTOs.CreateRequest request) {
        if (itemRepository.existsBySku(request.getSku())) {
            throw new BadRequestException("SKU already exists: " + request.getSku());
        }

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + request.getCategoryId()));

        Long currentUserId = currentUserProvider.getCurrentUser().getId();

        Item item = Item.builder()
                .category(category)
                .name(request.getName())
                .brand(request.getBrand())
                .sku(request.getSku())
                .unitOfMeasure(request.getUnitOfMeasure())
                .mrp(request.getMrp())
                .countryOfOrigin(request.getCountryOfOrigin())
                .rawMaterialsUsed(request.getRawMaterialsUsed())
                .warrantyMonths(request.getWarrantyMonths())
                .termsAndCondition(request.getTermsAndCondition())
                .description(request.getDescription())
                .attributes(request.getAttributes())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        return mapToItemResponse(itemRepository.save(item));
    }

    @Override
    @Transactional
    public ItemDTOs.Response updateItem(Long id, ItemDTOs.UpdateRequest request) {
        Item item = itemRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + id));

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + request.getCategoryId()));

        Long currentUserId = currentUserProvider.getCurrentUser().getId();

        item.setCategory(category);
        item.setName(request.getName());
        item.setBrand(request.getBrand());
        item.setUnitOfMeasure(request.getUnitOfMeasure());
        item.setMrp(request.getMrp());
        item.setCountryOfOrigin(request.getCountryOfOrigin());
        item.setRawMaterialsUsed(request.getRawMaterialsUsed());
        item.setWarrantyMonths(request.getWarrantyMonths());
        item.setTermsAndCondition(request.getTermsAndCondition());
        item.setDescription(request.getDescription());
        item.setAttributes(request.getAttributes());
        item.setUpdatedBy(currentUserId);

        if (request.getIsActive() != null) {
            item.setIsActive(request.getIsActive());
        }

        return mapToItemResponse(itemRepository.save(item));
    }

    @Override
    @Transactional(readOnly = true)
    public ItemDTOs.Response getItemById(Long id) {
        Item item = itemRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + id));
        return mapToItemResponse(item);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ItemDTOs.Response> getAllItems(Pageable pageable) {
        return itemRepository.findAll(pageable)
                .map(this::mapToItemResponse);
    }

    @Override
    @Transactional
    public void deleteItem(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + id));
        item.setIsActive(false);
        item.setUpdatedBy(currentUserProvider.getCurrentUser().getId());
        itemRepository.save(item);
    }

    private CategoryDTOs.Response mapToCategoryResponse(Category category) {
        CategoryDTOs.Response res = new CategoryDTOs.Response();
        res.setId(category.getId());
        res.setName(category.getName());
        res.setCode(category.getCode());
        res.setDescription(category.getDescription());
        res.setIsActive(category.getIsActive());
        res.setCreatedAt(category.getCreatedAt());
        res.setUpdatedAt(category.getUpdatedAt());
        return res;
    }

    private ItemDTOs.Response mapToItemResponse(Item item) {
        ItemDTOs.Response res = new ItemDTOs.Response();
        res.setId(item.getId());
        res.setCategoryId(item.getCategory().getId());
        res.setCategoryName(item.getCategory().getName());
        res.setName(item.getName());
        res.setBrand(item.getBrand());
        res.setSku(item.getSku());
        res.setUnitOfMeasure(item.getUnitOfMeasure());
        res.setMrp(item.getMrp());
        res.setCountryOfOrigin(item.getCountryOfOrigin());
        res.setRawMaterialsUsed(item.getRawMaterialsUsed());
        res.setWarrantyMonths(item.getWarrantyMonths());
        res.setTermsAndCondition(item.getTermsAndCondition());
        res.setDescription(item.getDescription());
        res.setAttributes(item.getAttributes());
        res.setIsActive(item.getIsActive());
        res.setCreatedAt(item.getCreatedAt());
        res.setUpdatedAt(item.getUpdatedAt());
        res.setCreatedBy(item.getCreatedBy());
        res.setUpdatedBy(item.getUpdatedBy());
        return res;
    }
}