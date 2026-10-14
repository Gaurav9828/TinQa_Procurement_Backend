package com.tinqa.procurement.productapproval.service;

import com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;

import java.util.List;

public interface ProductApprovalService {

    ProductApprovalDTOs.Response submit(ProductApprovalDTOs.SubmitRequest request);

    List<ProductApprovalDTOs.Response> getByStatus(ProductApprovalStatus status);

    // productIds: comma-separated Ecommerce product ids (max 200)
    List<ProductApprovalDTOs.Response> getLatestForProducts(String productIds);

    List<ProductApprovalDTOs.Response> getHistory(Long ecommerceProductId);

    ProductApprovalDTOs.Response decide(Long id, ProductApprovalDTOs.DecisionRequest request);

    // After the UI wrote an approved UPDATE to Ecommerce; idempotent
    ProductApprovalDTOs.Response markApplied(Long id);
}
