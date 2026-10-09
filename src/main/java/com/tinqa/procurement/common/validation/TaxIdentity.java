package com.tinqa.procurement.common.validation;

/**
 * Request objects carrying Indian tax identifiers; checked together by {@link ValidTaxIdentity}.
 */
public interface TaxIdentity {
    String getGstin();

    String getPanNumber();
}
