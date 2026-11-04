package com.tinqa.procurement.barcode.service;

import com.tinqa.procurement.barcode.dto.BarcodeDTOs;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import com.tinqa.procurement.barcode.repository.BarcodeRepository;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store sales: the product on a unit's barcode, scanned or typed at the counter. One indexed read; only the product is
 * returned, never the unit's parts or where they came from.
 */
@Component
@RequiredArgsConstructor
public class StoreUnitLookup {

    private final BarcodeRepository barcodeRepository;

    /** 400 for a malformed code, 404 when no unit carries it, 409 for a component piece. */
    @Transactional(readOnly = true)
    public BarcodeDTOs.StoreUnit lookup(String rawCode) {
        String code = BarcodeCodes.normalize(rawCode);
        if (!BarcodeCodes.isValid(code)) {
            throw ApiErrors.of(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Barcode " + ValidationPatterns.BARCODE_MESSAGE + ".");
        }
        BarcodeRepository.UnitProduct unit = barcodeRepository.findLiveUnitProduct(code).orElseThrow(() ->
                ApiErrors.of(HttpStatus.NOT_FOUND, ErrorCodes.BARCODE_NOT_FOUND, "No product unit carries barcode " + code + "."));
        if (unit.getUnitType() != BarcodeUnitType.PRODUCT_UNIT) {
            throw ApiErrors.of(HttpStatus.CONFLICT, ErrorCodes.BARCODE_NOT_AVAILABLE, "Barcode " + code
                    + " is a component piece, not a product unit. Scan the barcode on the product.");
        }
        boolean inStock = unit.getStatus() == BarcodeStatus.IN_STOCK;
        return BarcodeDTOs.StoreUnit.builder()
                .barcode(unit.getCode())
                .productId(unit.getEcommerceProductId())
                .productTitle(unit.getProductTitle())
                .status(unit.getStatus())
                .inStock(inStock)
                .note(inStock ? null : "This unit is " + BarcodeRegistry.describe(unit.getStatus()) + ".")
                .build();
    }
}
