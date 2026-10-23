package com.tinqa.procurement.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.mail.StaffMailProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the approved company profile from the e-commerce service's public endpoint (GET {ecommerce}/company/profile)
 * and keeps it for a few minutes, so rendering many payslips costs one call. When the profile can't be read, the last
 * one read is used, else {@link CompanyLetterhead#FALLBACK}: a document never fails because of its header.
 */
@Slf4j
@Component
public class CompanyLetterheadProvider {

    private static final String PATH = "/company/profile";
    private static final Duration KEEP_FOR = Duration.ofMinutes(5);

    private final RestClient client;
    private final Clock clock;
    // The last profile read and when, swapped as one
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    private record Cached(CompanyLetterhead letterhead, Instant readAt) {
    }

    public CompanyLetterheadProvider(StaffMailProperties properties, Clock clock) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.clock = clock;
    }

    public CompanyLetterhead current() {
        Cached kept = cached.get();
        if (kept != null && kept.readAt().plus(KEEP_FOR).isAfter(clock.instant())) {
            return kept.letterhead();
        }
        try {
            JsonNode response = client.get().uri(PATH).retrieve().body(JsonNode.class);
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || data.isMissingNode() || text(data, "brandName") == null) {
                throw new IllegalStateException("the company profile response has no brand name");
            }
            CompanyLetterhead read = new CompanyLetterhead(text(data, "brandName"), text(data, "legalName"),
                    address(data.path("address")), text(data, "gstin"), text(data, "pan"), text(data, "supportEmail"),
                    text(data, "supportPhone"));
            cached.set(new Cached(read, clock.instant()));
            return read;
        } catch (RestClientException | IllegalStateException exception) {
            if (log.isWarnEnabled()) {
                log.warn("Company profile could not be read for document headers: {}", exception.getMessage());
            }
            return kept != null ? kept.letterhead() : CompanyLetterhead.FALLBACK;
        }
    }

    private static String address(JsonNode address) {
        if (address == null || address.isMissingNode() || address.isNull()) {
            return null;
        }
        String postal = Stream.of(text(address, "city"), text(address, "state")).filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        String pin = text(address, "postalCode");
        String line = Stream.of(text(address, "line1"), text(address, "line2"),
                        postal.isEmpty() ? null : postal + (pin == null ? "" : " " + pin))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        return line.isEmpty() ? null : line;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }
}
