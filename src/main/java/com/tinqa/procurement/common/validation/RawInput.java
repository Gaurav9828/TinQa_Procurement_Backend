package com.tinqa.procurement.common.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exempts a field from {@link InputSafety} checks. Only for secrets that are never stored or shown as text
 * (passwords); they must accept any character.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RawInput {
}
