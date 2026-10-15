package com.tinqa.procurement.dealer.service;

import com.tinqa.procurement.dealer.dto.DealerDTOs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;

/**
 * Dealers and the (DEALER type) categories they are mapped to. Categories themselves are managed by
 * {@link com.tinqa.procurement.category.service.CategoryService}.
 */
public interface DealerService {

    DealerDTOs.Response createDealer(DealerDTOs.CreateRequest request);

    DealerDTOs.Response updateDealer(Long id, DealerDTOs.UpdateRequest request);

    DealerDTOs.Response getDealerById(Long id);

    Page<DealerDTOs.Response> getAllDealers(Pageable pageable);

    /** Soft delete: the dealer is deactivated. */
    void deleteDealer(Long id);

    DealerDTOs.Response toggleDealerStatus(Long id);

    /** Adds categories to the ones the dealer already has. */
    DealerDTOs.Response assignCategories(Long dealerId, Set<Long> categoryIds);

    /** Replaces the dealer's categories. */
    DealerDTOs.Response updateDealerCategories(Long dealerId, Set<Long> categoryIds);

    DealerDTOs.Response removeCategoryFromDealer(Long dealerId, Long categoryId);
}
