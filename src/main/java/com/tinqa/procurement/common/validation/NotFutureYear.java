package com.tinqa.procurement.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.time.Year;

@Documented
@Constraint(validatedBy = NotFutureYear.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotFutureYear {
    String message() default "must be a year between {min} and the current year";

    int min() default 1800;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NotFutureYear, Integer> {
        private int min;

        @Override
        public void initialize(NotFutureYear annotation) {
            this.min = annotation.min();
        }

        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || (value >= min && value <= Year.now().getValue());
        }
    }
}
