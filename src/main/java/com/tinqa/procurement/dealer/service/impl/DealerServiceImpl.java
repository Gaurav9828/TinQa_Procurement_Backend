package com.tinqa.procurement.dealer.service.impl;

import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.category.enums.CategoryType;
import com.tinqa.procurement.category.service.CategoryService;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.dealer.dto.DealerDTOs;
import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.dealer.mapper.DealerMapper;
import com.tinqa.procurement.dealer.repository.DealerRepository;
import com.tinqa.procurement.dealer.service.DealerService;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class DealerServiceImpl implements DealerService {

    private final DealerRepository dealerRepository;
    private final CategoryService categoryService;
    private final CurrentUserProvider currentUserProvider;
    private final DealerMapper dealerMapper;

    @Override
    @Transactional
    public DealerDTOs.Response createDealer(DealerDTOs.CreateRequest request) {
        if (request.getGstin() != null && dealerRepository.existsByGstin(request.getGstin())) {
            throw new BadRequestException("GSTIN already registered: " + request.getGstin());
        }
        List<Category> categories = categoryService.getCategories(request.getCategoryIds(), CategoryType.DEALER);
        Long currentUserId = currentUserProvider.getCurrentUserId();

        Dealer dealer = Dealer.builder()
                .name(request.getName())
                .tradeName(request.getTradeName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .alternatePhoneNumber(request.getAlternatePhoneNumber())
                .street(request.getStreet())
                .landmark(request.getLandmark())
                .city(request.getCity())
                .district(request.getDistrict())
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

        return dealerMapper.toResponse(dealerRepository.save(dealer));
    }

    @Override
    @Transactional
    public DealerDTOs.Response updateDealer(Long id, DealerDTOs.UpdateRequest request) {
        Dealer dealer = findWithCategories(id);
        List<Category> categories = categoryService.getCategories(request.getCategoryIds(), CategoryType.DEALER);

        dealer.setName(request.getName());
        dealer.setTradeName(request.getTradeName());
        dealer.setEmail(request.getEmail());
        dealer.setPhoneNumber(request.getPhoneNumber());
        dealer.setAlternatePhoneNumber(request.getAlternatePhoneNumber());
        dealer.setStreet(request.getStreet());
        dealer.setLandmark(request.getLandmark());
        dealer.setCity(request.getCity());
        dealer.setDistrict(request.getDistrict());
        dealer.setState(request.getState());
        dealer.setCountry(request.getCountry());
        dealer.setPincode(request.getPincode());
        dealer.setGoogleMapsUrl(request.getGoogleMapsUrl());
        dealer.setGstin(request.getGstin());
        dealer.setPanNumber(request.getPanNumber());
        dealer.setBusinessSince(request.getBusinessSince());
        dealer.setEmployeeCount(request.getEmployeeCount());
        // Flags are required columns: one left out of the request keeps its current value
        setIfPresent(request.getIsGstVerified(), dealer::setIsGstVerified);
        setIfPresent(request.getOffersShipping(), dealer::setOffersShipping);
        setIfPresent(request.getDoesBulkDealing(), dealer::setDoesBulkDealing);
        setIfPresent(request.getDoesWholesaleDealing(), dealer::setDoesWholesaleDealing);
        setIfPresent(request.getIsActive(), dealer::setIsActive);
        dealer.setCategories(new HashSet<>(categories));
        dealer.setUpdatedBy(currentUserProvider.getCurrentUserId());

        return dealerMapper.toResponse(dealerRepository.save(dealer));
    }

    @Override
    @Transactional(readOnly = true)
    public DealerDTOs.Response getDealerById(Long id) {
        return dealerMapper.toResponse(findWithCategories(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DealerDTOs.Response> getAllDealers(Pageable pageable) {
        return dealerRepository.findAll(pageable).map(dealerMapper::toResponse);
    }

    @Override
    @Transactional
    public void deleteDealer(Long id) {
        Dealer dealer = dealerRepository.findById(id).orElseThrow(() -> dealerNotFound(id));
        dealer.setIsActive(false);
        dealer.setUpdatedBy(currentUserProvider.getCurrentUserId());
        dealerRepository.save(dealer);
    }

    @Override
    @Transactional
    public DealerDTOs.Response toggleDealerStatus(Long id) {
        Dealer dealer = findWithCategories(id);
        dealer.setIsActive(!dealer.getIsActive());
        return save(dealer);
    }

    @Override
    @Transactional
    public DealerDTOs.Response assignCategories(Long dealerId, Set<Long> categoryIds) {
        Dealer dealer = findWithCategories(dealerId);
        dealer.getCategories().addAll(categoryService.getCategories(categoryIds, CategoryType.DEALER));
        return save(dealer);
    }

    @Override
    @Transactional
    public DealerDTOs.Response updateDealerCategories(Long dealerId, Set<Long> categoryIds) {
        Dealer dealer = findWithCategories(dealerId);
        List<Category> categories = categoryService.getCategories(categoryIds, CategoryType.DEALER);
        dealer.getCategories().clear();
        dealer.getCategories().addAll(categories);
        return save(dealer);
    }

    @Override
    @Transactional
    public DealerDTOs.Response removeCategoryFromDealer(Long dealerId, Long categoryId) {
        Dealer dealer = findWithCategories(dealerId);
        if (!dealer.getCategories().removeIf(category -> category.getId().equals(categoryId))) {
            throw new ResourceNotFoundException("Category ID " + categoryId + " is not mapped to Dealer ID " + dealerId);
        }
        return save(dealer);
    }

    private Dealer findWithCategories(Long id) {
        return dealerRepository.findByIdWithCategories(id).orElseThrow(() -> dealerNotFound(id));
    }

    // Records who made the change, then saves
    private DealerDTOs.Response save(Dealer dealer) {
        dealer.setUpdatedBy(currentUserProvider.getCurrentUserId());
        return dealerMapper.toResponse(dealerRepository.save(dealer));
    }

    private static ResourceNotFoundException dealerNotFound(Long id) {
        return new ResourceNotFoundException("Dealer not found with ID: " + id);
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
