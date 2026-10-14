package com.tinqa.procurement.productapproval.enums;

public enum ProductApprovalRequestType {
    // First request for the product
    CREATE,
    // New request after the previous one was REJECTED
    RESUBMISSION,
    // Change to an approved (live) product; applied to Ecommerce only after approval
    UPDATE
}
