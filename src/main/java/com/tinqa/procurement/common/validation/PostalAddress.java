package com.tinqa.procurement.common.validation;

/**
 * Request objects carrying an address; checked as a whole by {@link ValidAddress}.
 */
public interface PostalAddress {
    String getCity();

    String getState();

    String getCountry();

    String getPincode();
}
