package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.payroll.PayslipEmailSender;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** Monthly salaries: recorded as transferred, confirmed by someone else, then booked, emailed and downloadable. */
class PayrollIntegrationTest extends IntegrationTestSupport {

    private static final String PAYROLL = "/v1/admin/payroll";

    @Autowired
    private PayslipEmailSender payslipEmailSender;

    /** An employee (a delivery rider: no payroll powers) with a monthly salary and a personal email. */
    private record Worker(long userId, long employeeId, String username, String email, String token) {
    }

    private Worker worker(String salary) throws Exception {
        String username = uniq("rider");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        long employeeId = insertEmployee(userId, "DELIVERY_RIDER");
        String email = username + "@personal.test";
        jdbc.update("UPDATE employee SET salary_amount = ?, salary_currency = 'INR', personal_email = ? WHERE id = ?",
                new java.math.BigDecimal(salary), email, employeeId);
        return new Worker(userId, employeeId, username, email, login(username, SEED_PASSWORD));
    }

    private JsonNode record(Actor actor, int month, long employeeId, List<Map<String, Object>> lines, int expectedStatus) throws Exception {
        return post(PAYROLL + "/years/2025/months/" + month + "/payments", actor.token(), body(
                "transferDate", "2025-" + String.format("%02d", month) + "-28",
                "bankReference", "UTR" + next(),
                "entries", List.of(body("employeeId", employeeId, "lines", lines))), expectedStatus);
    }

    private JsonNode decide(Actor actor, long paymentId, String decision, int expectedStatus) throws Exception {
        return post(PAYROLL + "/payments/decision", actor.token(), body("paymentIds", List.of(paymentId), "decision", decision,
                "rejectionReason", "REJECTED".equals(decision) ? "Wrong amount" : null), expectedStatus);
    }

    private MvcResult pdf(String path, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token)).andReturn();
    }

    @Test
    void aConfirmedSalaryIsBookedEmailedAndDownloadableByTheEmployee() throws Exception {
        Worker worker = worker("50000");
        JsonNode recorded = data(record(l2a, 3, worker.employeeId(), List.of(
                body("kind", "EARNING", "label", "Festival bonus", "amount", 5000),
                body("kind", "DEDUCTION", "label", "TDS", "amount", 2000)), 201)).get(0);
        long paymentId = recorded.get("id").asLong();
        assertThat(recorded.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(recorded.get("totalEarnings").decimalValue()).isEqualByComparingTo("55000");
        assertThat(recorded.get("netPay").decimalValue()).isEqualByComparingTo("53000");

        // Nothing is downloadable, booked or emailed before confirmation
        assertThat(pdf(PAYROLL + "/payments/" + paymentId + "/payslip", l2a.token()).getResponse().getStatus()).isEqualTo(409);
        assertThat(data(get("/v1/payroll/my-payslips", worker.token(), 200))).isEmpty();

        // Maker-checker: the recorder can't confirm; a Level 1 can't confirm at all
        assertThat(decide(l2a, paymentId, "APPROVED", 403).get("errorCode").asText()).isEqualTo("SALARY_SELF_DECISION");
        decide(l1, paymentId, "APPROVED", 403);
        JsonNode confirmed = data(decide(l2b, paymentId, "APPROVED", 200)).get("payments").get(0);
        assertThat(confirmed.get("status").asText()).isEqualTo("APPROVED");
        assertThat(confirmed.get("payslipEmailTo").asText()).isEqualTo(worker.email());

        // The company account reads it once confirmed
        JsonNode feed = perform(MockMvcRequestBuilders.get("/v1/internal/finance/salary-events").param("limit", "500")
                .header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY), 200);
        assertThat(data(feed).get("events").findValuesAsText("postingKey")).contains("PROCUREMENT_SALARY:" + paymentId);

        // The payslip is emailed after the confirmation, as a PDF
        payslipEmailSender.sendDue();
        ArgumentCaptor<byte[]> attachment = ArgumentCaptor.forClass(byte[].class);
        verify(staffMailer).sendPayslip(eq(worker.email()), any(), eq("March 2025"), eq("Rs. 53,000.00"), any(), attachment.capture());
        assertThat(new String(attachment.getValue(), 0, 5)).isEqualTo("%PDF-");
        JsonNode month = data(get(PAYROLL + "/years/2025/months/3", l2a.token(), 200));
        JsonNode row = rowOf(month, worker.employeeId());
        assertThat(row.get("payment").get("payslipEmailedAt").isNull()).isFalse();

        // The employee lists and downloads their own payslip; the payments team downloads anyone's
        JsonNode mine = data(get("/v1/payroll/my-payslips", worker.token(), 200));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("netPay").decimalValue()).isEqualByComparingTo("53000");
        MvcResult own = pdf("/v1/payroll/my-payslips/" + paymentId + "/pdf?download=true", worker.token());
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(own.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(own.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(pdf(PAYROLL + "/payments/" + paymentId + "/payslip", l1.token()).getResponse().getStatus()).isEqualTo(200);

        // Nobody else reaches it as their own, and a rider can't open the payroll
        assertThat(pdf("/v1/payroll/my-payslips/" + paymentId + "/pdf", l1.token()).getResponse().getStatus()).isEqualTo(404);
        get(PAYROLL + "/years/2025", worker.token(), 403);
    }

    @Test
    void aRejectedSalaryCanBeRecordedAgainButNeverTwiceAtOnce() throws Exception {
        Worker worker = worker("30000");
        long first = data(record(l2a, 4, worker.employeeId(), List.of(), 201)).get(0).get("id").asLong();
        assertThat(record(l1, 4, worker.employeeId(), List.of(), 409).get("errorCode").asText()).isEqualTo("SALARY_NOT_PAYABLE");

        decide(l2b, first, "REJECTED", 200);
        decide(l2b, first, "APPROVED", 409);
        long second = data(record(l1, 4, worker.employeeId(), List.of(), 201)).get(0).get("id").asLong();
        JsonNode row = rowOf(data(get(PAYROLL + "/years/2025/months/4", l2a.token(), 200)), worker.employeeId());
        assertThat(row.get("payment").get("id").asLong()).isEqualTo(second);

        // Withdrawn before anyone decided: the month is open again
        post(PAYROLL + "/payments/" + second + "/withdraw", l1.token(), null, 200);
        decide(l2b, second, "APPROVED", 409);
        record(l2a, 4, worker.employeeId(), List.of(), 201);
    }

    @Test
    void deductionsCantExceedTheSalaryAndFutureMonthsAreClosed() throws Exception {
        Worker worker = worker("10000");
        record(l2a, 5, worker.employeeId(), List.of(body("kind", "DEDUCTION", "label", "Unpaid leave", "amount", 10000)), 409);
        post(PAYROLL + "/years/2100/months/1/payments", l2a.token(), body("transferDate", "2025-01-01",
                "entries", List.of(body("employeeId", worker.employeeId()))), 409);
        // A salary-less employee is listed with the reason and can't be recorded
        jdbc.update("UPDATE employee SET salary_amount = NULL WHERE id = ?", worker.employeeId());
        JsonNode row = rowOf(data(get(PAYROLL + "/years/2025/months/5", l2a.token(), 200)), worker.employeeId());
        assertThat(row.get("payable").asBoolean()).isFalse();
        record(l2a, 5, worker.employeeId(), List.of(), 409);
    }

    @Test
    void nobodyConfirmsTheirOwnSalary() throws Exception {
        jdbc.update("UPDATE employee SET salary_amount = 90000, salary_currency = 'INR' WHERE id = ?", l2a.employeeId());
        long paymentId = data(post(PAYROLL + "/years/2025/months/6/payments", l2b.token(), body("transferDate", "2025-06-30",
                "entries", List.of(body("employeeId", l2a.employeeId()))), 201)).get(0).get("id").asLong();
        assertThat(decide(l2a, paymentId, "APPROVED", 403).get("errorCode").asText()).isEqualTo("SALARY_SELF_DECISION");
        jdbc.update("UPDATE employee SET salary_amount = NULL WHERE id = ?", l2a.employeeId());
    }

    @Test
    void theYearShowsEachMonthsProgress() throws Exception {
        Worker worker = worker("20000");
        long paymentId = data(record(l2a, 7, worker.employeeId(), List.of(), 201)).get(0).get("id").asLong();
        decide(l2b, paymentId, "APPROVED", 200);
        JsonNode year = data(get(PAYROLL + "/years/2025", l2a.token(), 200));
        assertThat(year.get("months")).hasSize(12);
        JsonNode july = year.get("months").get(6);
        assertThat(july.get("approved").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(july.get("state").asText()).isIn("IN_PROGRESS", "AWAITING_APPROVAL", "PAID");
        assertThat(data(get(PAYROLL + "/years/2100", l2a.token(), 200)).get("months").get(0).get("state").asText())
                .isEqualTo("UPCOMING");
    }

    private static JsonNode rowOf(JsonNode month, long employeeId) {
        for (JsonNode row : month.get("rows")) {
            if (row.get("employeeId").asLong() == employeeId) {
                return row;
            }
        }
        throw new AssertionError("No row for employee " + employeeId);
    }
}
