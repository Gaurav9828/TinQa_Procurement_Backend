package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.tinqa.procurement.clientdemo.DemoBillService.Acting.ATTENDEE;
import static com.tinqa.procurement.clientdemo.DemoBillService.Acting.MANAGER;

// Client demos & meetings: planned (quotation), approved, run with their bills, completed and approved again, then
// booked. Attending staff read the demos they are tagged on, and record their own bills, under /v1/client-demos/mine
@RestController
@RequiredArgsConstructor
public class ClientDemoController {

    private static final String BASE = "/v1/admin/client-demos";
    private static final String MINE = "/v1/client-demos/mine";

    private final ClientDemoService demoService;
    private final DemoBillService billService;
    private final DemoUnitPlanner unitPlanner;
    private final DemoUnitService unitService;

    // ------------------------------------------------------------------ demo managers and approvers

    @RequiresPermission(Permission.DEMO_VIEW)
    @GetMapping(BASE)
    public ResponseEntity<ApiResponse<List<ClientDemoDTOs.Summary>>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<DemoStatus> status) {
        return ApiResponses.ok(demoService.list(from, to, status), "Client demos fetched successfully");
    }

    // The Approvals Hub queue: quotations, completions and corrections waiting for a decision
    @RequiresPermission(Permission.DEMO_APPROVE)
    @GetMapping(BASE + "/waiting")
    public ResponseEntity<ApiResponse<List<ClientDemoDTOs.Summary>>> waiting() {
        return ApiResponses.ok(demoService.waiting(), "Client demos awaiting approval fetched successfully");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @GetMapping(BASE + "/staff")
    public ResponseEntity<ApiResponse<List<ClientDemoDTOs.StaffMember>>> staff() {
        return ApiResponses.ok(demoService.staffOptions(), "Staff fetched successfully");
    }

    // The product's units in stock, for picking the units a demo on these days takes (demoId: the demo being changed)
    @RequiresPermission(Permission.DEMO_MANAGE)
    @GetMapping(BASE + "/units")
    public ResponseEntity<ApiResponse<List<ClientDemoDTOs.UnitOption>>> unitOptions(
            @RequestParam Long productId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(defaultValue = "1") int days,
            @RequestParam(required = false) Long demoId) {
        return ApiResponses.ok(unitPlanner.options(productId, startDate, days, demoId), "Units fetched successfully");
    }

    // One unit by its barcode (typed or scanned), with its product
    @RequiresPermission(Permission.DEMO_MANAGE)
    @GetMapping(BASE + "/units/lookup")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.UnitOption>> unitLookup(
            @RequestParam String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(defaultValue = "1") int days,
            @RequestParam(required = false) Long demoId) {
        return ApiResponses.ok(unitPlanner.lookup(code, startDate, days, demoId), "Unit found");
    }

    @RequiresPermission(Permission.DEMO_VIEW)
    @GetMapping(BASE + "/{id}")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> get(@PathVariable Long id) {
        return ApiResponses.ok(demoService.get(id), "Client demo fetched successfully");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(BASE)
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> create(@Valid @RequestBody ClientDemoDTOs.DemoRequest request) {
        ClientDemoDTOs.Detail demo = demoService.create(request);
        return ApiResponses.created(demo, demo.getReference() + " saved as a draft");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PutMapping(BASE + "/{id}")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> update(@PathVariable Long id,
                                                                     @Valid @RequestBody ClientDemoDTOs.DemoRequest request) {
        ClientDemoDTOs.Detail demo = demoService.update(id, request);
        return ApiResponses.ok(demo, demo.getStatus() == DemoStatus.QUOTATION_PENDING
                ? demo.getReference() + " updated. The new budget or length needs approval again."
                : demo.getReference() + " updated");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @DeleteMapping(BASE + "/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        demoService.deleteDraft(id);
        return ApiResponses.ok(null, "Draft deleted");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(BASE + "/{id}/submit")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> submit(@PathVariable Long id) {
        ClientDemoDTOs.Detail demo = demoService.submitQuotation(id);
        return ApiResponses.ok(demo, "Quotation sent. Someone else has to approve it before the visit.");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(BASE + "/{id}/complete")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> complete(@PathVariable Long id,
                                                                       @Valid @RequestBody ClientDemoDTOs.CompleteRequest request) {
        ClientDemoDTOs.Detail demo = demoService.submitCompletion(id, request);
        return ApiResponses.ok(demo, "Completion sent. The costs are booked once someone else approves it.");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(BASE + "/{id}/withdraw")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> withdraw(@PathVariable Long id) {
        return ApiResponses.ok(demoService.withdraw(id), "Withdrawn from approval");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(BASE + "/{id}/cancel")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> cancel(@PathVariable Long id,
                                                                     @Valid @RequestBody ClientDemoDTOs.ReasonRequest request) {
        return ApiResponses.ok(demoService.cancel(id, request), "Client demo cancelled");
    }

    @RequiresPermission(Permission.DEMO_APPROVE)
    @PostMapping(BASE + "/{id}/decision")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> decide(@PathVariable Long id,
                                                                     @Valid @RequestBody ClientDemoDTOs.DecisionRequest request) {
        ClientDemoDTOs.Detail demo = demoService.decide(id, request);
        String message = switch (demo.getStatus()) {
            case APPROVED -> "Quotation approved. The attending staff were told.";
            case COMPLETED -> "Completion approved and booked to the company account.";
            default -> "Rejected and sent back with your reason.";
        };
        return ApiResponses.ok(demo, message);
    }

    @RequiresPermission(Permission.DEMO_COMPLETED_EDIT)
    @PostMapping(BASE + "/{id}/reopen")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> reopen(@PathVariable Long id,
                                                                     @Valid @RequestBody ClientDemoDTOs.ReasonRequest request) {
        return ApiResponses.ok(demoService.reopen(id, request), "Reopened for correction. Send it for completion again when done.");
    }

    // The demo's units leave stock (here and on the storefront) on one of its days, and come back afterwards
    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(BASE + "/{id}/units/hand-out")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> handOut(@PathVariable Long id,
                                                                      @Valid @RequestBody ClientDemoDTOs.UnitsRequest request) {
        return ApiResponses.ok(unitService.handOut(id, request, false), "Handed out: the units are out of stock until they come back");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(BASE + "/{id}/units/hand-back")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> handBack(@PathVariable Long id,
                                                                       @Valid @RequestBody ClientDemoDTOs.UnitsRequest request) {
        return ApiResponses.ok(unitService.handBack(id, request, false), "Handed back: the units are in stock again");
    }

    @RequiresPermission(Permission.DEMO_UNIT_WRITE_OFF)
    @PostMapping(BASE + "/{id}/units/write-off")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> writeOff(@PathVariable Long id,
                                                                       @Valid @RequestBody ClientDemoDTOs.WriteOffRequest request) {
        return ApiResponses.ok(unitService.writeOff(id, request), "Unit written off");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(BASE + "/{id}/expenses")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> addExpense(@PathVariable Long id,
                                                                         @Valid @RequestBody ClientDemoDTOs.ExpenseRequest request) {
        return ApiResponses.created(billService.add(MANAGER, id, request), "Bill added");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PutMapping(BASE + "/{id}/expenses/{expenseId}")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> updateExpense(@PathVariable Long id, @PathVariable Long expenseId,
                                                                            @Valid @RequestBody ClientDemoDTOs.ExpenseRequest request) {
        return ApiResponses.ok(billService.update(MANAGER, id, expenseId, request), "Bill updated");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @DeleteMapping(BASE + "/{id}/expenses/{expenseId}")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> removeExpense(@PathVariable Long id, @PathVariable Long expenseId,
                                                                            @RequestParam(required = false) String reason) {
        return ApiResponses.ok(billService.remove(MANAGER, id, expenseId, reason), "Bill removed");
    }

    @RequiresPermission(Permission.DEMO_MANAGE)
    @PostMapping(value = BASE + "/{id}/expenses/{expenseId}/invoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> attachInvoice(@PathVariable Long id, @PathVariable Long expenseId,
                                                                            @RequestPart("file") MultipartFile file) {
        return ApiResponses.ok(billService.attachInvoice(MANAGER, id, expenseId, file), "Invoice attached");
    }

    @RequiresPermission(Permission.DEMO_VIEW)
    @GetMapping(BASE + "/{id}/expenses/{expenseId}/invoice")
    public ResponseEntity<Resource> invoice(@PathVariable Long id, @PathVariable Long expenseId) {
        return file(billService.invoice(MANAGER, id, expenseId));
    }

    // ------------------------------------------------------------------ attending staff

    @RequiresPermission(Permission.DEMO_VIEW_OWN)
    @GetMapping(MINE)
    public ResponseEntity<ApiResponse<List<ClientDemoDTOs.Summary>>> mine() {
        return ApiResponses.ok(demoService.mine(), "Your client demos fetched successfully");
    }

    @RequiresPermission(Permission.DEMO_VIEW_OWN)
    @GetMapping(MINE + "/{id}")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> mine(@PathVariable Long id) {
        return ApiResponses.ok(demoService.mine(id), "Client demo fetched successfully");
    }

    @RequiresPermission(Permission.DEMO_UNITS_HANDLE_OWN)
    @PostMapping(MINE + "/{id}/units/hand-out")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> handOutMine(@PathVariable Long id,
                                                                          @Valid @RequestBody ClientDemoDTOs.UnitsRequest request) {
        return ApiResponses.ok(unitService.handOut(id, request, true), "Handed out: the units are out of stock until they come back");
    }

    @RequiresPermission(Permission.DEMO_UNITS_HANDLE_OWN)
    @PostMapping(MINE + "/{id}/units/hand-back")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> handBackMine(@PathVariable Long id,
                                                                           @Valid @RequestBody ClientDemoDTOs.UnitsRequest request) {
        return ApiResponses.ok(unitService.handBack(id, request, true), "Handed back: the units are in stock again");
    }

    @RequiresPermission(Permission.DEMO_EXPENSE_RECORD_OWN)
    @PostMapping(MINE + "/{id}/expenses")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> addMyExpense(@PathVariable Long id,
                                                                           @Valid @RequestBody ClientDemoDTOs.ExpenseRequest request) {
        return ApiResponses.created(billService.add(ATTENDEE, id, request), "Bill added");
    }

    @RequiresPermission(Permission.DEMO_EXPENSE_RECORD_OWN)
    @PutMapping(MINE + "/{id}/expenses/{expenseId}")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> updateMyExpense(@PathVariable Long id, @PathVariable Long expenseId,
                                                                              @Valid @RequestBody ClientDemoDTOs.ExpenseRequest request) {
        return ApiResponses.ok(billService.update(ATTENDEE, id, expenseId, request), "Bill updated");
    }

    @RequiresPermission(Permission.DEMO_EXPENSE_RECORD_OWN)
    @DeleteMapping(MINE + "/{id}/expenses/{expenseId}")
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> removeMyExpense(@PathVariable Long id, @PathVariable Long expenseId,
                                                                              @RequestParam(required = false) String reason) {
        return ApiResponses.ok(billService.remove(ATTENDEE, id, expenseId, reason), "Bill removed");
    }

    @RequiresPermission(Permission.DEMO_EXPENSE_RECORD_OWN)
    @PostMapping(value = MINE + "/{id}/expenses/{expenseId}/invoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ClientDemoDTOs.Detail>> attachMyInvoice(@PathVariable Long id, @PathVariable Long expenseId,
                                                                              @RequestPart("file") MultipartFile file) {
        return ApiResponses.ok(billService.attachInvoice(ATTENDEE, id, expenseId, file), "Invoice attached");
    }

    @RequiresPermission(Permission.DEMO_VIEW_OWN)
    @GetMapping(MINE + "/{id}/expenses/{expenseId}/invoice")
    public ResponseEntity<Resource> myInvoice(@PathVariable Long id, @PathVariable Long expenseId) {
        return file(billService.invoice(ATTENDEE, id, expenseId));
    }

    // Always served as an attachment with the stored (validated) content type
    private static ResponseEntity<Resource> file(DocumentDownload download) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }
}
