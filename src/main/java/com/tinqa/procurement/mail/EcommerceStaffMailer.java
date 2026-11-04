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

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends staff emails through the e-commerce service (POST /internal/staff-emails with the shared internal API key),
 * so both portals mail through the one server a Super Admin sets up in Company Profile › Email. That service builds
 * the email and the staff-portal link from the token; nothing here is queued or stored (payslips are retried by
 * PayslipEmailJob, meeting emails by MeetingJobs).
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

    @Override
    public void sendPayslip(String to, String name, String period, String netPay, String filename, byte[] pdf) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "PAYSLIP");
        body.put("to", to);
        body.put("name", name);
        body.put("period", period);
        body.put("amount", netPay);
        body.put("attachmentName", filename);
        body.put("attachmentBase64", Base64.getEncoder().encodeToString(pdf));
        send(body);
    }

    @Override
    public void sendMeetingEmail(MeetingEmail email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "MEETING_" + email.kind());
        body.put("to", email.to());
        body.put("name", email.name());
        Map<String, Object> meeting = new LinkedHashMap<>();
        meeting.put("id", email.meetingId());
        meeting.put("code", email.code());
        meeting.put("uid", email.uid());
        meeting.put("sequence", email.sequence());
        meeting.put("title", email.title());
        meeting.put("subject", email.subject());
        meeting.put("mode", email.mode());
        meeting.put("meetingType", email.meetingType());
        meeting.put("startsAt", email.startsAt() == null ? null : email.startsAt().toString());
        meeting.put("endsAt", email.endsAt() == null ? null : email.endsAt().toString());
        meeting.put("joinUrl", email.joinUrl());
        meeting.put("placeName", email.placeName());
        meeting.put("address", email.address());
        meeting.put("googleMapsUrl", email.googleMapsUrl());
        meeting.put("organizerName", email.organizerName());
        meeting.put("organizerEmail", email.organizerEmail());
        meeting.put("cancelReason", email.cancelReason());
        meeting.put("declineBy", email.declineBy() == null ? null : email.declineBy().toString());
        body.put("meeting", meeting);
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
