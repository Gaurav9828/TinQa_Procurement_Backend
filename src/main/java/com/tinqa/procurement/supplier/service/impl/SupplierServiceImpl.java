package com.tinqa.procurement.supplier.service.impl;

import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.category.enums.CategoryType;
import com.tinqa.procurement.category.service.CategoryService;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.supplier.dto.SupplierDTOs;
import com.tinqa.procurement.supplier.dto.SupplierFilter;
import com.tinqa.procurement.supplier.entity.Supplier;
import com.tinqa.procurement.supplier.login.SupplierLoginService;
import com.tinqa.procurement.supplier.mapper.SupplierMapper;
import com.tinqa.procurement.supplier.repository.SupplierRepository;
import com.tinqa.procurement.supplier.service.SupplierService;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository supplierRepository;
    private final CategoryService categoryService;
    private final CurrentUserProvider currentUserProvider;
    private final SupplierMapper supplierMapper;
    private final SupplierLoginService supplierLoginService;

    @Override
    @Transactional
    public SupplierDTOs.Response createSupplier(SupplierDTOs.CreateRequest request) {
        if (request.getGstin() != null && supplierRepository.existsByGstin(request.getGstin())) {
            throw new BadRequestException("GSTIN already registered: " + request.getGstin());
        }
        List<Category> categories = categoryService.getCategories(request.getCategoryIds(), CategoryType.ITEM);
        Long currentUserId = currentUserProvider.getCurrentUserId();

        Supplier supplier = Supplier.builder()
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

        return supplierMapper.toResponse(supplierRepository.save(supplier));
    }

    @Override
    @Transactional
    public SupplierDTOs.Response updateSupplier(Long id, SupplierDTOs.UpdateRequest request) {
        Supplier supplier = findWithCategories(id);
        List<Category> categories = categoryService.getCategories(request.getCategoryIds(), CategoryType.ITEM);

        supplier.setName(request.getName());
        supplier.setTradeName(request.getTradeName());
        supplier.setEmail(request.getEmail());
        supplier.setPhoneNumber(request.getPhoneNumber());
        supplier.setAlternatePhoneNumber(request.getAlternatePhoneNumber());
        supplier.setStreet(request.getStreet());
        supplier.setLandmark(request.getLandmark());
        supplier.setCity(request.getCity());
        supplier.setDistrict(request.getDistrict());
        supplier.setState(request.getState());
        supplier.setCountry(request.getCountry());
        supplier.setPincode(request.getPincode());
        supplier.setGoogleMapsUrl(request.getGoogleMapsUrl());
        supplier.setGstin(request.getGstin());
        supplier.setPanNumber(request.getPanNumber());
        supplier.setBusinessSince(request.getBusinessSince());
        supplier.setEmployeeCount(request.getEmployeeCount());
        // Flags are required columns: one left out of the request keeps its current value
        setIfPresent(request.getIsGstVerified(), supplier::setIsGstVerified);
        setIfPresent(request.getOffersShipping(), supplier::setOffersShipping);
        setIfPresent(request.getDoesBulkDealing(), supplier::setDoesBulkDealing);
        setIfPresent(request.getDoesWholesaleDealing(), supplier::setDoesWholesaleDealing);
        setIfPresent(request.getIsActive(), supplier::setIsActive);
        supplier.setCategories(new HashSet<>(categories));
        supplier.setUpdatedBy(currentUserProvider.getCurrentUserId());

        Supplier saved = supplierRepository.save(supplier);
        supplierLoginService.supplierStatusChanged(saved);
        return supplierMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierDTOs.Response getSupplierById(Long id) {
        return supplierMapper.toResponse(findWithCategories(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierDTOs.Response> getAllSuppliers(SupplierFilter filter, Pageable pageable) {
        return supplierRepository.findAll(matching(filter), pageable).map(supplierMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getSupplierCities() {
        // "Pune" and "pune " are one city; the first spelling seen (alphabetical) is shown
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String city : supplierRepository.findDistinctCities()) {
            String trimmed = OptionalText.blankToNull(city);
            if (trimmed != null) byKey.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        return List.copyOf(byKey.values());
    }

    @Override
    @Transactional
    public void deleteSupplier(Long id) {
        Supplier supplier = supplierRepository.findById(id).orElseThrow(() -> supplierNotFound(id));
        supplier.setIsActive(false);
        supplier.setUpdatedBy(currentUserProvider.getCurrentUserId());
        supplierRepository.save(supplier);
        // A removed supplier can no longer sign in
        supplierLoginService.supplierStatusChanged(supplier);
    }

    @Override
    @Transactional
    public SupplierDTOs.Response toggleSupplierStatus(Long id) {
        Supplier supplier = findWithCategories(id);
        supplier.setIsActive(!supplier.getIsActive());
        SupplierDTOs.Response response = save(supplier);
        // An inactive supplier's login stops working (and its sessions end) until the supplier is active again
        supplierLoginService.supplierStatusChanged(supplier);
        return response;
    }

    @Override
    @Transactional
    public SupplierDTOs.Response assignCategories(Long supplierId, Set<Long> categoryIds) {
        Supplier supplier = findWithCategories(supplierId);
        supplier.getCategories().addAll(categoryService.getCategories(categoryIds, CategoryType.ITEM));
        return save(supplier);
    }

    @Override
    @Transactional
    public SupplierDTOs.Response updateSupplierCategories(Long supplierId, Set<Long> categoryIds) {
        Supplier supplier = findWithCategories(supplierId);
        List<Category> categories = categoryService.getCategories(categoryIds, CategoryType.ITEM);
        supplier.getCategories().clear();
        supplier.getCategories().addAll(categories);
        return save(supplier);
    }

    @Override
    @Transactional
    public SupplierDTOs.Response removeCategoryFromSupplier(Long supplierId, Long categoryId) {
        Supplier supplier = findWithCategories(supplierId);
        if (!supplier.getCategories().removeIf(category -> category.getId().equals(categoryId))) {
            throw new ResourceNotFoundException("Category ID " + categoryId + " is not mapped to Supplier ID " + supplierId);
        }
        return save(supplier);
    }

    private Supplier findWithCategories(Long id) {
        return supplierRepository.findByIdWithCategories(id).orElseThrow(() -> supplierNotFound(id));
    }

    // Records who made the change, then saves
    private SupplierDTOs.Response save(Supplier supplier) {
        supplier.setUpdatedBy(currentUserProvider.getCurrentUserId());
        return supplierMapper.toResponse(supplierRepository.save(supplier));
    }

    private static Specification<Supplier> matching(SupplierFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            String term = OptionalText.lowerCaseOrNull(filter.search());
            if (term != null) {
                String like = "%" + term + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("tradeName")), like),
                        cb.like(cb.lower(root.get("gstin")), like),
                        cb.like(cb.lower(root.get("panNumber")), like),
                        cb.like(cb.lower(root.get("phoneNumber")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("city")), like)));
            }
            if (filter.categoryIds() != null && !filter.categoryIds().isEmpty()) {
                // EXISTS keeps one row per supplier, so paging and counts stay right
                Subquery<Long> inCategory = query.subquery(Long.class);
                Root<Supplier> supplier = inCategory.correlate(root);
                Join<Supplier, Category> category = supplier.join("categories");
                inCategory.select(category.get("id")).where(category.get("id").in(filter.categoryIds()));
                predicates.add(cb.exists(inCategory));
            }
            List<String> cities = filter.cities() == null ? List.of() : filter.cities().stream()
                    .map(OptionalText::lowerCaseOrNull).filter(c -> c != null).toList();
            if (!cities.isEmpty()) {
                predicates.add(cb.lower(cb.trim(root.get("city"))).in(cities));
            }
            if (filter.isActive() != null) {
                predicates.add(cb.equal(root.get("isActive"), filter.isActive()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static ResourceNotFoundException supplierNotFound(Long id) {
        return new ResourceNotFoundException("Supplier not found with ID: " + id);
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
