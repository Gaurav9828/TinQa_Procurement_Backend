package com.tinqa.procurement.supplier.service;

import com.tinqa.procurement.supplier.dto.SupplierDTOs;
import com.tinqa.procurement.supplier.dto.SupplierFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;

/**
 * Suppliers and the (SUPPLIER type) categories they are mapped to. Categories themselves are managed by
 * {@link com.tinqa.procurement.category.service.CategoryService}.
 */
public interface SupplierService {

    SupplierDTOs.Response createSupplier(SupplierDTOs.CreateRequest request);

    SupplierDTOs.Response updateSupplier(Long id, SupplierDTOs.UpdateRequest request);

    SupplierDTOs.Response getSupplierById(Long id);

    Page<SupplierDTOs.Response> getAllSuppliers(SupplierFilter filter, Pageable pageable);

    /** Distinct supplier cities (case-insensitive), sorted, for the list filter. */
    List<String> getSupplierCities();

    /** Soft delete: the supplier is deactivated. */
    void deleteSupplier(Long id);

    SupplierDTOs.Response toggleSupplierStatus(Long id);

    /** Adds categories to the ones the supplier already has. */
    SupplierDTOs.Response assignCategories(Long supplierId, Set<Long> categoryIds);

    /** Replaces the supplier's categories. */
    SupplierDTOs.Response updateSupplierCategories(Long supplierId, Set<Long> categoryIds);

    SupplierDTOs.Response removeCategoryFromSupplier(Long supplierId, Long categoryId);
}
