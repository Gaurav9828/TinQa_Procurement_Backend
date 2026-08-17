package com.tinqa.procurement.dealer.service;

import com.tinqa.procurement.dealer.dto.DealerDTOs;
import com.tinqa.procurement.item.dto.CategoryDTOs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;

public interface DealerService {

    // Category operations
    CategoryDTOs.Response createCategory(CategoryDTOs.CreateRequest request);
    List<CategoryDTOs.Response> getAllCategories();

    // Dealer CRUD & State operations
    DealerDTOs.Response createDealer(DealerDTOs.CreateRequest request);
    DealerDTOs.Response updateDealer(Long id, DealerDTOs.UpdateRequest request);
    DealerDTOs.Response getDealerById(Long id);
    Page<DealerDTOs.Response> getAllDealers(Pageable pageable);
    void deleteDealer(Long id);
    DealerDTOs.Response toggleDealerStatus(Long id);

    // Dealer-Category Mapping operations
    DealerDTOs.Response assignCategories(Long dealerId, Set<Long> categoryIds);
    DealerDTOs.Response updateDealerCategories(Long dealerId, Set<Long> categoryIds);
    DealerDTOs.Response removeCategoryFromDealer(Long dealerId, Long categoryId);
}