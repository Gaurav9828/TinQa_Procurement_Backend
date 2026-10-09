package com.tinqa.procurement.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Only https links to Google Maps, so stored links cannot point users at look-alike (phishing) sites.
 */
@Documented
@Constraint(validatedBy = GoogleMapsUrl.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface GoogleMapsUrl {
    String message() default "must be an https Google Maps link (maps.google.com, google.com/maps, maps.app.goo.gl or goo.gl/maps)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<GoogleMapsUrl, String> {

        private static final Set<String> MAPS_HOSTS = Set.of("maps.google.com", "maps.google.co.in", "maps.app.goo.gl");
        private static final Set<String> GOOGLE_HOSTS = Set.of("google.com", "www.google.com", "google.co.in", "www.google.co.in");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || value.isBlank()) {
                return true;
            }
            if (value.length() > 500 || value.chars().anyMatch(Character::isWhitespace)) {
                return false;
            }
            try {
                URI uri = new URI(value);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                        || uri.getHost() == null) {
                    return false;
                }
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                String path = uri.getPath() == null ? "" : uri.getPath();
                return MAPS_HOSTS.contains(host)
                        || (GOOGLE_HOSTS.contains(host) && (path.equals("/maps") || path.startsWith("/maps/")))
                        || (host.equals("goo.gl") && path.startsWith("/maps/"));
            } catch (URISyntaxException exception) {
                return false;
            }
        }
    }
}
