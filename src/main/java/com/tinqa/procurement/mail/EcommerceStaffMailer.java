package com.tinqa.procurement.mail;

import com.tinqa.procurement.security.config.InternalApiProperties;
import com.tinqa.procurement.security.filter.InternalApiKeyFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends staff emails through the e-commerce service (POST /internal/staff-emails with the shared internal API key),
 * so both portals mail through the one server a Super Admin sets up in Company Profile › Email. That service builds
 * the email and the staff-portal link from the token; nothing here is queued or stored.
 */
@Slf4j
@Component
public class EcommerceStaffMailer implements StaffMailer {

    private static final String PATH = "/internal/staff-emails";
    private static final int SERVICE_UNAVAILABLE = 503;

    private final RestClient client;
    private final String apiKey;

    public EcommerceStaffMailer(StaffMailProperties properties, InternalApiProperties internalApi) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.apiKey = internalApi.key();
    }

    @Override
    public void sendVerificationCode(String to, String name, String code, long validMinutes) {
        Map<String, Object> body = body("VERIFICATION_CODE", to, name, validMinutes);
        body.put("code", code);
        send(body);
    }

    @Override
    public void sendPasswordLink(String to, String name, String token, boolean setup, long validMinutes) {
        Map<String, Object> body = body(setup ? "PASSWORD_SETUP" : "PASSWORD_RESET", to, name, validMinutes);
        body.put("token", token);
        send(body);
    }

    private static Map<String, Object> body(String type, String to, String name, long validMinutes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("to", to);
        body.put("name", name);
        body.put("validMinutes", validMinutes);
        return body;
    }

    private void send(Map<String, Object> body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new StaffMailException("tinqa.internal-api.key is not set, so staff emails can't be sent.", true, null);
        }
        try {
            client.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(InternalApiKeyFilter.HEADER, apiKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            log.warn("Staff email {} to {} refused ({}): {}", body.get("type"), body.get("to"), status.value(),
                    exception.getResponseBodyAsString());
            throw new StaffMailException("The email could not be sent.", status.value() == SERVICE_UNAVAILABLE, exception);
        } catch (RestClientException exception) {
            log.warn("Staff email {} to {} failed: {}", body.get("type"), body.get("to"), exception.getMessage());
            throw new StaffMailException("The email service could not be reached.", false, exception);
        }
    }
}
