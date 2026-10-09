package com.tinqa.procurement.common.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Allows a link in this field (all other {@link InputSafety} rules still apply). Pair it with a constraint
 * that pins down which links are acceptable, e.g. {@link GoogleMapsUrl}.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface UrlInput {
}
