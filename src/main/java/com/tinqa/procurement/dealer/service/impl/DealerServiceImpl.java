package com.tinqa.procurement.dealer.service.impl;

import com.tinqa.procurement.common.entity.Category;
import com.tinqa.procurement.common.enums.CategoryType;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.repository.CategoryRepository;
import com.tinqa.procurement.dealer.dto.DealerDTOs;
import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.dealer.repository.DealerRepository;
import com.tinqa.procurement.dealer.service.DealerService;
import com.tinqa.procurement.item.dto.CategoryDTOs;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DealerServiceImpl implements DealerService {

    private final DealerRepository dealerRepository;
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
                .type(CategoryType.DEALER)
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
        return categoryRepository.findByType(CategoryType.DEALER).stream()
                .map(this::mapToCategoryResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public DealerDTOs.Response createDealer(DealerDTOs.CreateRequest request) {
        if (request.getGstin() != null && dealerRepository.existsByGstin(request.getGstin())) {
            throw new BadRequestException("GSTIN already registered: " + request.getGstin());
        }

        List<Category> categories = categoryRepository.findAllById(request.getCategoryIds());
        if (categories.size() != request.getCategoryIds().size()) {
            throw new ResourceNotFoundException("One or more Category IDs are invalid");
        }

        Long currentUserId = currentUserProvider.getCurrentUser().getId();

        Dealer dealer = Dealer.builder()
                .name(request.getName())
                .tradeName(request.getTradeName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .alternatePhoneNumber(request.getAlternatePhoneNumber())
                .street(request.getStreet())
                .landmark(request.getLandmark())
                .city(request.getCity())
                .state(request.getState())
                .country(request.getCountry())
                .pincode(request.getPincode())
                .googleMapsUrl(request.getGoogleMapsUrl())
                .gstin(request.getGstin())
                .isGstVerified(Boolean.TRUE.equals(request.getIsGstVerified()))
                .panNumber(request.getPanNumber())
                .businessSince(request.getBusinessSince())
                .employeeCount(request.getEmployeeCount())
                .offersShipping(Boolean.TRUE.equals(request.getOffersShipping()))
                .doesBulkDealing(Boolean.TRUE.equals(request.getDoesBulkDealing()))
                .doesWholesaleDealing(Boolean.TRUE.equals(request.getDoesWholesaleDealing()))
                .categories(new HashSet<>(categories))
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        return mapToDealerResponse(dealerRepository.save(dealer));
    }

    @Override
    @Transactional
    public DealerDTOs.Response updateDealer(Long id, DealerDTOs.UpdateRequest request) {
        Dealer dealer = dealerRepository.findByIdWithCategories(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with ID: " + id));

        List<Category> categories = categoryRepository.findAllById(request.getCategoryIds());
        if (categories.size() != request.getCategoryIds().size()) {
            throw new ResourceNotFoundException("One or more Category IDs are invalid");
        }

        Long currentUserId = currentUserProvider.getCurrentUser().getId();

        dealer.setName(request.getName());
        dealer.setTradeName(request.getTradeName());
        dealer.setEmail(request.getEmail());
        dealer.setPhoneNumber(request.getPhoneNumber());
        dealer.setAlternatePhoneNumber(request.getAlternatePhoneNumber());
        dealer.setStreet(request.getStreet());
        dealer.setLandmark(request.getLandmark());
        dealer.setCity(request.getCity());
        dealer.setState(request.getState());
        dealer.setCountry(request.getCountry());
        dealer.setPincode(request.getPincode());
        dealer.setGoogleMapsUrl(request.getGoogleMapsUrl());
        dealer.setGstin(request.getGstin());
        dealer.setIsGstVerified(request.getIsGstVerified());
        dealer.setPanNumber(request.getPanNumber());
        dealer.setBusinessSince(request.getBusinessSince());
        dealer.setEmployeeCount(request.getEmployeeCount());
        dealer.setOffersShipping(request.getOffersShipping());
        dealer.setDoesBulkDealing(request.getDoesBulkDealing());
        dealer.setDoesWholesaleDealing(request.getDoesWholesaleDealing());
        dealer.setCategories(new HashSet<>(categories));
        dealer.setUpdatedBy(currentUserId);

        if (request.getIsActive() != null) {
            dealer.setIsActive(request.getIsActive());
        }

        return mapToDealerResponse(dealerRepository.save(dealer));
    }

    @Override
    @Transactional(readOnly = true)
    public DealerDTOs.Response getDealerById(Long id) {
        Dealer dealer = dealerRepository.findByIdWithCategories(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with ID: " + id));
        return mapToDealerResponse(dealer);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DealerDTOs.Response> getAllDealers(Pageable pageable) {
        return dealerRepository.findAll(pageable)
                .map(this::mapToDealerResponse);
    }

    @Override
    @Transactional
    public void deleteDealer(Long id) {
        Dealer dealer = dealerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with ID: " + id));
        dealer.setIsActive(false);
        dealer.setUpdatedBy(currentUserProvider.getCurrentUser().getId());
        dealerRepository.save(dealer);
    }

    @Override
    @Transactional
    public DealerDTOs.Response toggleDealerStatus(Long id) {
        Dealer dealer = dealerRepository.findByIdWithCategories(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with ID: " + id));

        dealer.setIsActive(!dealer.getIsActive());
        dealer.setUpdatedBy(currentUserProvider.getCurrentUser().getId());
        return mapToDealerResponse(dealerRepository.save(dealer));
    }

    @Override
    @Transactional
    public DealerDTOs.Response assignCategories(Long dealerId, Set<Long> categoryIds) {
        Dealer dealer = dealerRepository.findByIdWithCategories(dealerId)
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with ID: " + dealerId));

        List<Category> categoriesToAdd = categoryRepository.findAllById(categoryIds);
        if (categoriesToAdd.size() != categoryIds.size()) {
            throw new ResourceNotFoundException("One or more Category IDs are invalid");
        }

        dealer.getCategories().addAll(categoriesToAdd);
        dealer.setUpdatedBy(currentUserProvider.getCurrentUser().getId());

        return mapToDealerResponse(dealerRepository.save(dealer));
    }

    @Override
    @Transactional
    public DealerDTOs.Response updateDealerCategories(Long dealerId, Set<Long> categoryIds) {
        Dealer dealer = dealerRepository.findByIdWithCategories(dealerId)
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with ID: " + dealerId));

        List<Category> newCategories = categoryRepository.findAllById(categoryIds);
        if (newCategories.size() != categoryIds.size()) {
            throw new ResourceNotFoundException("One or more Category IDs are invalid");
        }

        dealer.getCategories().clear();
        dealer.getCategories().addAll(newCategories);
        dealer.setUpdatedBy(currentUserProvider.getCurrentUser().getId());

        return mapToDealerResponse(dealerRepository.save(dealer));
    }

    @Override
    @Transactional
    public DealerDTOs.Response removeCategoryFromDealer(Long dealerId, Long categoryId) {
        Dealer dealer = dealerRepository.findByIdWithCategories(dealerId)
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with ID: " + dealerId));

        boolean removed = dealer.getCategories().removeIf(cat -> cat.getId().equals(categoryId));
        if (!removed) {
            throw new ResourceNotFoundException("Category ID " + categoryId + " is not mapped to Dealer ID " + dealerId);
        }

        dealer.setUpdatedBy(currentUserProvider.getCurrentUser().getId());
        return mapToDealerResponse(dealerRepository.save(dealer));
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

    private DealerDTOs.Response mapToDealerResponse(Dealer dealer) {
        DealerDTOs.Response res = new DealerDTOs.Response();
        res.setId(dealer.getId());
        res.setName(dealer.getName());
        res.setTradeName(dealer.getTradeName());
        res.setEmail(dealer.getEmail());
        res.setPhoneNumber(dealer.getPhoneNumber());
        res.setAlternatePhoneNumber(dealer.getAlternatePhoneNumber());
        res.setStreet(dealer.getStreet());
        res.setLandmark(dealer.getLandmark());
        res.setCity(dealer.getCity());
        res.setState(dealer.getState());
        res.setCountry(dealer.getCountry());
        res.setPincode(dealer.getPincode());
        res.setGoogleMapsUrl(dealer.getGoogleMapsUrl());
        res.setGstin(dealer.getGstin());
        res.setIsGstVerified(dealer.getIsGstVerified());
        res.setPanNumber(dealer.getPanNumber());
        res.setBusinessSince(dealer.getBusinessSince());
        res.setEmployeeCount(dealer.getEmployeeCount());
        res.setOffersShipping(dealer.getOffersShipping());
        res.setDoesBulkDealing(dealer.getDoesBulkDealing());
        res.setDoesWholesaleDealing(dealer.getDoesWholesaleDealing());
        res.setIsActive(dealer.getIsActive());
        res.setCreatedAt(dealer.getCreatedAt());
        res.setUpdatedAt(dealer.getUpdatedAt());
        res.setCreatedBy(dealer.getCreatedBy());
        res.setUpdatedBy(dealer.getUpdatedBy());

        Set<DealerDTOs.CategorySummary> categorySummaries = dealer.getCategories().stream().map(cat -> {
            DealerDTOs.CategorySummary cs = new DealerDTOs.CategorySummary();
            cs.setId(cat.getId());
            cs.setName(cat.getName());
            cs.setCode(cat.getCode());
            return cs;
        }).collect(Collectors.toSet());

        res.setCategories(categorySummaries);
        return res;
    }
}