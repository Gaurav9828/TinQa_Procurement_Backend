package com.tinqa.procurement.barcode.controller;

import com.tinqa.procurement.barcode.dto.BarcodeDTOs;
import com.tinqa.procurement.barcode.service.StoreUnitLookup;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store sales agents find the product they hold by scanning its barcode. Agents can't see the barcode registry
 * (BARCODE_VIEW), so this answers only which product the unit is.
 */
@RestController
@RequiredArgsConstructor
public class StoreUnitController {

    private final StoreUnitLookup storeUnitLookup;

    @RequiresPermission(Permission.STORE_ORDER_CREATE)
    @GetMapping("/v1/store-sales/units/lookup")
    public ResponseEntity<ApiResponse<BarcodeDTOs.StoreUnit>> lookup(@RequestParam String code) {
        return ApiResponses.ok(storeUnitLookup.lookup(code), "Unit found");
    }
}
