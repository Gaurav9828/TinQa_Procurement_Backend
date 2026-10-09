package com.tinqa.procurement.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounds free-form JSON maps (attributes, additionalInfo, taxBreakup): limited size and nesting, plain keys,
 * and only strings, numbers, booleans, lists and maps as values. String values are already screened by
 * {@link SafeStringDeserializer}; keys are checked here.
 */
@Documented
@Constraint(validatedBy = SafeJsonMap.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeJsonMap {
    String message() default "must have at most {maxEntries} entries, nesting depth {maxDepth}, keys of letters/numbers/space/_/./- "
            + "(max 100 characters) and text values of at most {maxValueLength} characters";

    int maxEntries() default 50;

    int maxDepth() default 3;

    int maxValueLength() default 1000;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<SafeJsonMap, Map<String, Object>> {

        private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 _.-]{0,99}$");

        private int maxEntries;
        private int maxDepth;
        private int maxValueLength;

        @Override
        public void initialize(SafeJsonMap annotation) {
            this.maxEntries = annotation.maxEntries();
            this.maxDepth = annotation.maxDepth();
            this.maxValueLength = annotation.maxValueLength();
        }

        @Override
        public boolean isValid(Map<String, Object> value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            int[] entries = {0};
            return validMap(value, 1, entries);
        }

        private boolean validMap(Map<?, ?> map, int depth, int[] entries) {
            if (depth > maxDepth) {
                return false;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (++entries[0] > maxEntries || !(entry.getKey() instanceof String key) || !KEY.matcher(key).matches()
                        || InputSafety.findViolation(key) != null || !validValue(entry.getValue(), depth, entries)) {
                    return false;
                }
            }
            return true;
        }

        private boolean validValue(Object value, int depth, int[] entries) {
            if (value == null || value instanceof Number || value instanceof Boolean) {
                return true;
            }
            if (value instanceof String text) {
                return text.length() <= maxValueLength;
            }
            if (value instanceof Map<?, ?> nested) {
                return validMap(nested, depth + 1, entries);
            }
            if (value instanceof Collection<?> list) {
                if (depth + 1 > maxDepth) {
                    return false;
                }
                for (Object element : list) {
                    if (++entries[0] > maxEntries || !validValue(element, depth + 1, entries)) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
    }
}
