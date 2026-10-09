package com.tinqa.procurement.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Country-aware address checks: for India the state must be a real state/UT and the PIN code 6 digits
 * not starting with 0; elsewhere a 3-10 character postal code. Errors are reported on the offending field.
 */
@Documented
@Constraint(validatedBy = ValidAddress.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAddress {
    String message() default "Invalid address";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidAddress, PostalAddress> {
        @Override
        public boolean isValid(PostalAddress address, ConstraintValidatorContext context) {
            if (address == null || address.getCountry() == null || !GeoData.isCountry(address.getCountry())) {
                return true; // country itself is reported by @ValidCountry / @NotBlank
            }
            boolean valid = true;
            context.disableDefaultConstraintViolation();
            if (GeoData.isIndia(address.getCountry())) {
                if (address.getState() != null && !address.getState().isBlank() && !GeoData.isIndianState(address.getState())) {
                    valid = fail(context, "state", "must be a valid Indian state or union territory, e.g. Uttar Pradesh");
                }
                if (address.getPincode() != null && !address.getPincode().matches("^[1-9][0-9]{5}$")) {
                    valid = fail(context, "pincode", "must be a valid 6-digit Indian PIN code not starting with 0");
                }
            } else if (address.getPincode() != null && !address.getPincode().matches("^[A-Za-z0-9][A-Za-z0-9 -]{1,8}[A-Za-z0-9]$")) {
                valid = fail(context, "pincode", "must be a valid postal code (3-10 letters, numbers, spaces or hyphens)");
            }
            return valid;
        }

        private boolean fail(ConstraintValidatorContext context, String field, String message) {
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
            return false;
        }
    }
}
