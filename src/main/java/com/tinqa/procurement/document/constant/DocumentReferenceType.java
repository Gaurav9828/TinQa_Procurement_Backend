package com.tinqa.procurement.document.constant;

public enum DocumentReferenceType {

    PRODUCT,
    SUPPLIER,
    WORKSHOP,
    INSPECTOR,
    EMPLOYEE,
    AUCTION,
    ORDER,
    DELIVERY,
    CLAIM,
    // A client demo's hotel, food or travel invoice (client_demo_expense.document_id)
    CLIENT_DEMO,
    // A screenshot or PDF proving a client outreach contact (a call log, an email, a WhatsApp chat)
    OUTREACH_CONTACT,
    // A proof (bank statement, screenshot) sent with a supplier payment, or the supplier's receipt for money paid back
    SUPPLIER_PAYMENT
}