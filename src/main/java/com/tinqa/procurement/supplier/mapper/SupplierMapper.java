package com.tinqa.procurement.supplier.mapper;

import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.supplier.dto.SupplierDTOs;
import com.tinqa.procurement.supplier.entity.Supplier;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class SupplierMapper {

    public SupplierDTOs.Response toResponse(Supplier supplier) {
        SupplierDTOs.Response response = new SupplierDTOs.Response();
        response.setId(supplier.getId());
        response.setName(supplier.getName());
        response.setTradeName(supplier.getTradeName());
        response.setEmail(supplier.getEmail());
        response.setPhoneNumber(supplier.getPhoneNumber());
        response.setAlternatePhoneNumber(supplier.getAlternatePhoneNumber());
        response.setStreet(supplier.getStreet());
        response.setLandmark(supplier.getLandmark());
        response.setCity(supplier.getCity());
        response.setDistrict(supplier.getDistrict());
        response.setState(supplier.getState());
        response.setCountry(supplier.getCountry());
        response.setPincode(supplier.getPincode());
        response.setGoogleMapsUrl(supplier.getGoogleMapsUrl());
        response.setGstin(supplier.getGstin());
        response.setIsGstVerified(supplier.getIsGstVerified());
        response.setPanNumber(supplier.getPanNumber());
        response.setBusinessSince(supplier.getBusinessSince());
        response.setEmployeeCount(supplier.getEmployeeCount());
        response.setOffersShipping(supplier.getOffersShipping());
        response.setDoesBulkDealing(supplier.getDoesBulkDealing());
        response.setDoesWholesaleDealing(supplier.getDoesWholesaleDealing());
        response.setCategories(supplier.getCategories().stream()
                .map(this::toCategorySummary)
                .collect(Collectors.toSet()));
        response.setIsActive(supplier.getIsActive());
        response.setCreatedAt(supplier.getCreatedAt());
        response.setUpdatedAt(supplier.getUpdatedAt());
        response.setCreatedBy(supplier.getCreatedBy());
        response.setUpdatedBy(supplier.getUpdatedBy());
        return response;
    }

    private SupplierDTOs.CategorySummary toCategorySummary(Category category) {
        SupplierDTOs.CategorySummary summary = new SupplierDTOs.CategorySummary();
        summary.setId(category.getId());
        summary.setName(category.getName());
        summary.setCode(category.getCode());
        return summary;
    }
}
