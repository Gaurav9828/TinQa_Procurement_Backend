package com.tinqa.procurement.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * GSTIN must have a real state code and a correct check digit, and when a PAN is also given it must be the
 * PAN embedded in the GSTIN (characters 3-12). Format checks stay on the fields themselves.
 */
@Documented
@Constraint(validatedBy = ValidTaxIdentity.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTaxIdentity {
    String message() default "Invalid tax identity";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidTaxIdentity, TaxIdentity> {

        private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

        @Override
        public boolean isValid(TaxIdentity value, ConstraintValidatorContext context) {
            if (value == null || value.getGstin() == null || !value.getGstin().matches(ValidationPatterns.GSTIN)) {
                return true; // missing / badly formatted GSTIN is reported by the field's own @Pattern
            }
            String gstin = value.getGstin();
            context.disableDefaultConstraintViolation();
            int stateCode = Integer.parseInt(gstin.substring(0, 2));
            if (!((stateCode >= 1 && stateCode <= 38) || stateCode == 97 || stateCode == 99)) {
                return fail(context, "gstin", "has an invalid state code (first two digits must be 01-38, 97 or 99)");
            }
            if (gstin.charAt(14) != checkCharacter(gstin)) {
                return fail(context, "gstin", "is not a valid GSTIN (check digit does not match)");
            }
            String pan = value.getPanNumber();
            if (pan != null && !pan.isBlank() && !pan.equals(gstin.substring(2, 12))) {
                return fail(context, "panNumber", "must match the PAN inside the GSTIN (" + gstin.substring(2, 12) + ")");
            }
            return true;
        }

        // GSTN check-digit algorithm (weighted base-36 sum over the first 14 characters)
        static char checkCharacter(String gstin) {
            int sum = 0;
            for (int i = 0; i < 14; i++) {
                int product = CHARSET.indexOf(gstin.charAt(i)) * (i % 2 == 0 ? 1 : 2);
                sum += product / 36 + product % 36;
            }
            return CHARSET.charAt((36 - sum % 36) % 36);
        }

        private boolean fail(ConstraintValidatorContext context, String field, String message) {
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
            return false;
        }
    }
}
