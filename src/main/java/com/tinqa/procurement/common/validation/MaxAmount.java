package com.tinqa.procurement.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;

import java.lang.annotation.*;

/**
 * Money amount of at most ₹1,00,00,000 (1 crore) with up to 2 decimal places.
 */
@Documented
@DecimalMax(ValidationPatterns.MAX_AMOUNT)
@Digits(integer = 8, fraction = 2)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxAmount {
    String message() default "cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
