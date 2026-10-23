package com.tinqa.procurement.payroll;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Monthly salaries (Payments & Chalans): recorded as transferred, confirmed by someone else, then booked and emailed
// as payslips. Employees read their own payslips under /v1/payroll/my-payslips
@RestController
@RequiredArgsConstructor
public class PayrollController {

    private static final String BASE = "/v1/admin/payroll";
    private static final String MINE = "/v1/payroll/my-payslips";

    private final PayrollService payrollService;

    @RequiresPermission(Permission.PAYMENT_VIEW)
    @GetMapping(BASE + "/years/{year}")
    public ResponseEntity<ApiResponse<PayrollDTOs.YearSummary>> year(@PathVariable int year) {
        return ApiResponses.ok(payrollService.yearSummary(year), "Salary year fetched successfully");
    }

    @RequiresPermission(Permission.PAYMENT_VIEW)
    @GetMapping(BASE + "/years/{year}/months/{month}")
    public ResponseEntity<ApiResponse<PayrollDTOs.MonthView>> month(@PathVariable int year, @PathVariable int month) {
        return ApiResponses.ok(payrollService.monthView(year, month), "Salary month fetched successfully");
    }

    @RequiresPermission(Permission.PAYMENT_RECORD)
    @PostMapping(BASE + "/years/{year}/months/{month}/payments")
    public ResponseEntity<ApiResponse<List<PayrollDTOs.PaymentResponse>>> record(@PathVariable int year, @PathVariable int month,
                                                                                @Valid @RequestBody PayrollDTOs.RecordRequest request) {
        List<PayrollDTOs.PaymentResponse> recorded = payrollService.record(year, month, request);
        return ApiResponses.created(recorded, (recorded.size() == 1 ? "Salary" : recorded.size() + " salaries")
                + " recorded as transferred. Someone else has to confirm before the payslip is sent.");
    }

    @RequiresPermission(Permission.PAYMENT_RECORD)
    @PostMapping(BASE + "/payments/{paymentId}/withdraw")
    public ResponseEntity<ApiResponse<PayrollDTOs.PaymentResponse>> withdraw(@PathVariable Long paymentId) {
        return ApiResponses.ok(payrollService.withdraw(paymentId), "Salary payment withdrawn");
    }

    @RequiresPermission(Permission.PAYMENT_APPROVE)
    @PostMapping(BASE + "/payments/decision")
    public ResponseEntity<ApiResponse<PayrollDTOs.DecisionResult>> decide(@Valid @RequestBody PayrollDTOs.DecisionRequest request) {
        PayrollDTOs.DecisionResult result = payrollService.decide(request);
        boolean approved = request.getDecision() == PayrollDTOs.Decision.APPROVED;
        String what = result.getDecided() == 1 ? "Salary" : result.getDecided() + " salaries";
        return ApiResponses.ok(result, approved ? what + " confirmed. Payslips are being emailed." : what + " rejected");
    }

    @RequiresPermission(Permission.PAYMENT_RECORD)
    @PostMapping(BASE + "/payments/{paymentId}/payslip/resend")
    public ResponseEntity<ApiResponse<PayrollDTOs.PaymentResponse>> resend(@PathVariable Long paymentId) {
        PayrollDTOs.PaymentResponse payment = payrollService.resendPayslip(paymentId);
        return ApiResponses.ok(payment, payment.getPayslipEmailTo() == null
                ? "The employee has no email address. Add one in Employee Management first."
                : "The payslip will be emailed to " + payment.getPayslipEmailTo() + " within a minute.");
    }

    @RequiresPermission(Permission.PAYMENT_VIEW)
    @GetMapping(BASE + "/payments/{paymentId}/payslip")
    public ResponseEntity<byte[]> payslip(@PathVariable Long paymentId, @RequestParam(defaultValue = "false") boolean download) {
        return pdf(payrollService.payslip(paymentId), download);
    }

    @RequiresPermission(Permission.PAYSLIP_VIEW_OWN)
    @GetMapping(MINE)
    public ResponseEntity<ApiResponse<List<PayrollDTOs.MyPayslip>>> myPayslips() {
        return ApiResponses.ok(payrollService.myPayslips(), "Payslips fetched successfully");
    }

    @RequiresPermission(Permission.PAYSLIP_VIEW_OWN)
    @GetMapping(MINE + "/{paymentId}/pdf")
    public ResponseEntity<byte[]> myPayslip(@PathVariable Long paymentId, @RequestParam(defaultValue = "false") boolean download) {
        return pdf(payrollService.myPayslip(paymentId), download);
    }

    // Rendered on every request and never cached: a payslip is personal
    private static ResponseEntity<byte[]> pdf(PayslipComposer.PayslipFile file, boolean download) {
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.filename()).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }
}
