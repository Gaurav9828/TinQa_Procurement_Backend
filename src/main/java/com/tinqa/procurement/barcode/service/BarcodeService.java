package com.tinqa.procurement.barcode.service;

import com.tinqa.procurement.barcode.dto.BarcodeDTOs;
import com.tinqa.procurement.barcode.enums.BarcodeRequestStatus;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;

import java.util.List;

/**
 * Looking barcodes up and tracing them, and the requests that change locked barcodes (decided by a Super Admin).
 */
public interface BarcodeService {

    /** Everything known about the unit carrying the code (the most recent one, when no live unit carries it). */
    BarcodeDTOs.Trace trace(String code);

    /** The units of one stock entry, or of one item with a status (IN_STOCK when omitted). */
    List<BarcodeDTOs.Unit> list(Long stockId, Long itemId, BarcodeStatus status);

    /** Every unit of an Ecommerce product, with the pieces fitted into each. */
    List<BarcodeDTOs.ProductUnit> productUnits(Long ecommerceProductId);

    /** A Super Admin's request is applied straight away; anyone else's waits for a Super Admin. */
    BarcodeDTOs.RequestResponse createRequest(BarcodeDTOs.CreateRequest request);

    List<BarcodeDTOs.RequestResponse> getRequests(BarcodeRequestStatus status);

    BarcodeDTOs.RequestResponse decide(Long id, BarcodeDTOs.DecisionRequest request);
}
