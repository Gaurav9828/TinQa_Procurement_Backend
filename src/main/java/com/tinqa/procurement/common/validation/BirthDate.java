package com.tinqa.procurement.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.time.LocalDate;
import java.time.Period;

/**
 * A date of birth giving an age between {minAge} and {maxAge} today (never in the future).
 */
@Documented
@Constraint(validatedBy = BirthDate.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface BirthDate {
    String message() default "must be a past date for a person aged {minAge} to {maxAge}";

    int minAge() default 18;

    int maxAge() default 100;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BirthDate, LocalDate> {
        private int minAge;
        private int maxAge;

        @Override
        public void initialize(BirthDate annotation) {
            this.minAge = annotation.minAge();
            this.maxAge = annotation.maxAge();
        }

        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            LocalDate today = LocalDate.now();
            if (!value.isBefore(today)) {
                return false;
            }
            int age = Period.between(value, today).getYears();
            return age >= minAge && age <= maxAge;
        }
    }
}
