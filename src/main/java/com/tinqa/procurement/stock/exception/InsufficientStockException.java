package com.tinqa.procurement.stock.exception;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

@Getter
public class InsufficientStockException extends ApiException {

    private final Long itemId;
    private final BigDecimal requestedUnits;
    private final BigDecimal availableUnits;

    public InsufficientStockException(String message, Long itemId, BigDecimal requestedUnits, BigDecimal availableUnits) {
        super(message, ErrorCodes.INSUFFICIENT_STOCK, HttpStatus.CONFLICT);
        this.itemId = itemId;
        this.requestedUnits = requestedUnits;
        this.availableUnits = availableUnits;
    }
}
