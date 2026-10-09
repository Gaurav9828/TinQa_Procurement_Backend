package com.tinqa.procurement.common.validation;

import java.util.*;

/**
 * Reference lists for address validation.
 */
public final class GeoData {

    private static final Set<String> INDIA_NAMES = Set.of("india", "bharat", "in", "ind", "republic of india");

    private static final Set<String> INDIAN_STATES = normalizedSet(List.of(
            "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
            "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
            "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
            "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal",
            // Union territories
            "Andaman and Nicobar Islands", "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu", "Delhi",
            "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry",
            // Common alternative spellings
            "NCT of Delhi", "National Capital Territory of Delhi", "Orissa", "Pondicherry", "Uttaranchal",
            "Andaman & Nicobar Islands", "Jammu & Kashmir", "Dadra & Nagar Haveli and Daman & Diu"));

    private static final Set<String> COUNTRIES = buildCountries();

    private GeoData() {
    }

    public static boolean isIndia(String country) {
        return country != null && INDIA_NAMES.contains(normalize(country));
    }

    public static boolean isIndianState(String state) {
        return state != null && INDIAN_STATES.contains(normalize(state));
    }

    // Accepts English country names, ISO alpha-2 / alpha-3 codes and a few common short forms
    public static boolean isCountry(String country) {
        return country != null && COUNTRIES.contains(normalize(country));
    }

    private static Set<String> buildCountries() {
        Set<String> countries = new HashSet<>(INDIA_NAMES);
        for (String code : Locale.getISOCountries()) {
            Locale locale = Locale.of("", code);
            countries.add(normalize(code));
            countries.add(normalize(locale.getISO3Country()));
            countries.add(normalize(locale.getDisplayCountry(Locale.ENGLISH)));
        }
        countries.addAll(normalizedSet(List.of("USA", "United States of America", "UK", "Great Britain", "England",
                "Scotland", "Wales", "Northern Ireland", "UAE", "South Korea", "North Korea", "Russia", "Vietnam",
                "Czech Republic", "Ivory Coast", "Burma", "Holland", "Turkey", "Turkiye", "Macau", "Hong Kong")));
        return Set.copyOf(countries);
    }

    private static Set<String> normalizedSet(Collection<String> values) {
        Set<String> set = new HashSet<>();
        values.forEach(value -> set.add(normalize(value)));
        return Set.copyOf(set);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[.\\s]+", " ").trim();
    }
}
