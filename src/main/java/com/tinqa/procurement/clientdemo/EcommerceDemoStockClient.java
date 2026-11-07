package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.mail.StaffMailProperties;
import com.tinqa.procurement.security.config.InternalApiProperties;
import com.tinqa.procurement.security.filter.InternalApiKeyFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link DemoStockClient} over the e-commerce service's internal API (POST /internal/products/demo-stock with the shared
 * internal API key). Called outside any database transaction.
 */
@Slf4j
@Component
public class EcommerceDemoStockClient implements DemoStockClient {

    private static final String PATH = "/internal/products/demo-stock";

    private final RestClient client;
    private final String apiKey;

    public EcommerceDemoStockClient(StaffMailProperties ecommerce, InternalApiProperties internalApi) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) ecommerce.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) ecommerce.readTimeout().toMillis());
        this.client = RestClient.builder().baseUrl(ecommerce.baseUrl()).requestFactory(requestFactory).build();
        this.apiKey = internalApi.key();
    }

    @Override
    public void move(String key, Direction direction, String demoReference, Map<Long, Integer> quantities, String performedBy) {
        if (apiKey == null || apiKey.isBlank()) {
            throw unavailable("tinqa.internal-api.key is not set, so the storefront's stock can't be updated.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("direction", direction.name());
        body.put("demoReference", demoReference);
        body.put("performedBy", performedBy);
        body.put("lines", quantities.entrySet().stream()
                .map(line -> Map.of("productId", line.getKey(), "quantity", line.getValue())).toList());
        try {
            client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).header(InternalApiKeyFilter.HEADER, apiKey)
                    .body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (log.isWarnEnabled()) {
                log.warn("Demo stock move {} refused ({}): {}", key, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            }
            if (exception.getStatusCode().is4xxClientError()) {
                throw new ApiException(messageOf(exception), ErrorCodes.DEMO_STOCK_REFUSED, HttpStatus.CONFLICT);
            }
            throw unavailable("The storefront could not update its stock. Try again in a moment.");
        } catch (RestClientException exception) {
            if (log.isWarnEnabled()) {
                log.warn("Demo stock move {} failed: {}", key, exception.getMessage());
            }
            // The move may have been applied before the answer was lost: undo it, so the caller can simply try again
            reverse(key);
            throw unavailable("The storefront could not be reached to update its stock. Try again in a moment.");
        }
    }

    @Override
    public void reverse(String key) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        try {
            client.post().uri(PATH + "/{key}/reverse", key).header(InternalApiKeyFilter.HEADER, apiKey).retrieve().toBodilessEntity();
        } catch (RestClientException exception) {
            if (log.isErrorEnabled()) {
                log.error("Demo stock move {} could not be reversed; check the storefront's stock: {}", key, exception.getMessage());
            }
        }
    }

    private static ApiException unavailable(String message) {
        return new ApiException(message, ErrorCodes.DEMO_STOCK_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
    }

    // The storefront's own message ("Only 1 unit of X is left to sell"), else a general one
    private static String messageOf(RestClientResponseException exception) {
        try {
            Map<?, ?> body = exception.getResponseBodyAs(Map.class);
            Object message = body == null ? null : body.get("message");
            if (message instanceof String text && !text.isBlank()) {
                return text;
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "The storefront refused the stock change.";
    }
}
