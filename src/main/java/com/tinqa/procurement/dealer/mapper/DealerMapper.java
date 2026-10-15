package com.tinqa.procurement.dealer.mapper;

import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.dealer.dto.DealerDTOs;
import com.tinqa.procurement.dealer.entity.Dealer;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class DealerMapper {

    public DealerDTOs.Response toResponse(Dealer dealer) {
        DealerDTOs.Response response = new DealerDTOs.Response();
        response.setId(dealer.getId());
        response.setName(dealer.getName());
        response.setTradeName(dealer.getTradeName());
        response.setEmail(dealer.getEmail());
        response.setPhoneNumber(dealer.getPhoneNumber());
        response.setAlternatePhoneNumber(dealer.getAlternatePhoneNumber());
        response.setStreet(dealer.getStreet());
        response.setLandmark(dealer.getLandmark());
        response.setCity(dealer.getCity());
        response.setDistrict(dealer.getDistrict());
        response.setState(dealer.getState());
        response.setCountry(dealer.getCountry());
        response.setPincode(dealer.getPincode());
        response.setGoogleMapsUrl(dealer.getGoogleMapsUrl());
        response.setGstin(dealer.getGstin());
        response.setIsGstVerified(dealer.getIsGstVerified());
        response.setPanNumber(dealer.getPanNumber());
        response.setBusinessSince(dealer.getBusinessSince());
        response.setEmployeeCount(dealer.getEmployeeCount());
        response.setOffersShipping(dealer.getOffersShipping());
        response.setDoesBulkDealing(dealer.getDoesBulkDealing());
        response.setDoesWholesaleDealing(dealer.getDoesWholesaleDealing());
        response.setCategories(dealer.getCategories().stream()
                .map(this::toCategorySummary)
                .collect(Collectors.toSet()));
        response.setIsActive(dealer.getIsActive());
        response.setCreatedAt(dealer.getCreatedAt());
        response.setUpdatedAt(dealer.getUpdatedAt());
        response.setCreatedBy(dealer.getCreatedBy());
        response.setUpdatedBy(dealer.getUpdatedBy());
        return response;
    }

    private DealerDTOs.CategorySummary toCategorySummary(Category category) {
        DealerDTOs.CategorySummary summary = new DealerDTOs.CategorySummary();
        summary.setId(category.getId());
        summary.setName(category.getName());
        summary.setCode(category.getCode());
        return summary;
    }
}
