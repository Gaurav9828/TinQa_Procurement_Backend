package com.tinqa.procurement.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.util.Currency;

@Documented
@Constraint(validatedBy = ValidCurrency.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrency {
    String message() default "must be a 3-letter ISO currency code, e.g. INR";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidCurrency, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (!value.matches("^[A-Z]{3}$")) {
                return false;
            }
            try {
                Currency.getInstance(value);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }
}
