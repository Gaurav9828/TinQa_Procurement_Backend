package com.tinqa.procurement.outreach;

import com.tinqa.procurement.clientdemo.ClientCategory;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

// Client outreach: the businesses we pitch to and every call, email, WhatsApp message or visit with them
@RestController
@RequestMapping("/v1/outreach")
@RequiredArgsConstructor
public class OutreachController {

    private final OutreachService outreachService;

    // ------------------------------------------------------------------ businesses

    @RequiresPermission(Permission.OUTREACH_VIEW)
    @GetMapping("/businesses")
    public ResponseEntity<ApiResponse<Page<OutreachDTOs.Business>>> businesses(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ClientCategory businessType,
            @RequestParam(required = false) Interest interest,
            @RequestParam(defaultValue = "false") boolean followUpDue,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponses.ok(outreachService.businesses(search, businessType, interest, followUpDue, pageable),
                "Businesses fetched successfully");
    }

    @RequiresPermission(Permission.OUTREACH_LOG)
    @PostMapping("/businesses")
    public ResponseEntity<ApiResponse<OutreachDTOs.Business>> createBusiness(@Valid @RequestBody OutreachDTOs.BusinessRequest request) {
        OutreachDTOs.Business business = outreachService.createBusiness(request);
        return ApiResponses.created(business, business.name() + " added");
    }

    @RequiresPermission(Permission.OUTREACH_VIEW)
    @GetMapping("/businesses/{id}")
    public ResponseEntity<ApiResponse<OutreachDTOs.Business>> business(@PathVariable Long id) {
        return ApiResponses.ok(outreachService.business(id), "Business fetched successfully");
    }

    @RequiresPermission(Permission.OUTREACH_LOG)
    @PutMapping("/businesses/{id}")
    public ResponseEntity<ApiResponse<OutreachDTOs.Business>> updateBusiness(@PathVariable Long id,
                                                                             @Valid @RequestBody OutreachDTOs.BusinessRequest request) {
        OutreachDTOs.Business business = outreachService.updateBusiness(id, request);
        return ApiResponses.ok(business, business.name() + " updated");
    }

    @RequiresPermission(Permission.OUTREACH_VIEW)
    @GetMapping("/businesses/{id}/contacts")
    public ResponseEntity<ApiResponse<Page<OutreachDTOs.Contact>>> contactsOf(@PathVariable Long id,
                                                                              @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponses.ok(outreachService.contactsOf(id, pageable), "Contacts fetched successfully");
    }

    @RequiresPermission(Permission.OUTREACH_LOG)
    @PostMapping("/businesses/{id}/contacts")
    public ResponseEntity<ApiResponse<OutreachDTOs.Contact>> logContact(@PathVariable Long id,
                                                                        @Valid @RequestBody OutreachDTOs.ContactRequest request) {
        return ApiResponses.created(outreachService.logContact(id, request), "Contact logged");
    }

    // ------------------------------------------------------------------ contacts

    @RequiresPermission(Permission.OUTREACH_VIEW)
    @GetMapping("/contacts")
    public ResponseEntity<ApiResponse<Page<OutreachDTOs.Contact>>> contacts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long loggedBy,
            @RequestParam(required = false) ContactChannel channel,
            @RequestParam(required = false) Interest interest,
            @RequestParam(defaultValue = "false") boolean mine,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponses.ok(outreachService.contacts(from, to, loggedBy, channel, interest, mine, pageable),
                "Contacts fetched successfully");
    }

    @RequiresPermission(Permission.OUTREACH_LOG)
    @PutMapping("/contacts/{id}")
    public ResponseEntity<ApiResponse<OutreachDTOs.Contact>> editContact(@PathVariable Long id,
                                                                         @Valid @RequestBody OutreachDTOs.ContactRequest request) {
        return ApiResponses.ok(outreachService.editContact(id, request), "Contact updated");
    }

    @RequiresPermission(Permission.OUTREACH_LOG)
    @PostMapping(value = "/contacts/{id}/proofs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<OutreachDTOs.Proof>> addProof(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return ApiResponses.created(outreachService.addProof(id, file), "Proof attached");
    }

    // Always served as an attachment with the stored (validated) content type
    @RequiresPermission(Permission.OUTREACH_VIEW)
    @GetMapping("/contacts/{id}/proofs/{proofId}")
    public ResponseEntity<Resource> proof(@PathVariable Long id, @PathVariable Long proofId) {
        DocumentDownload download = outreachService.proofFile(id, proofId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }

    @RequiresPermission(Permission.OUTREACH_LOG)
    @DeleteMapping("/contacts/{id}/proofs/{proofId}")
    public ResponseEntity<ApiResponse<Void>> removeProof(@PathVariable Long id, @PathVariable Long proofId) {
        outreachService.removeProof(id, proofId);
        return ApiResponses.ok("Proof removed");
    }

    // ------------------------------------------------------------------ dashboard

    @RequiresPermission(Permission.OUTREACH_VIEW)
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<OutreachDTOs.Summary>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponses.ok(outreachService.summary(from, to), "Outreach summary fetched successfully");
    }

    @RequiresPermission(Permission.OUTREACH_VIEW)
    @GetMapping("/follow-ups")
    public ResponseEntity<ApiResponse<Page<OutreachDTOs.Contact>>> followUps(@RequestParam(defaultValue = "false") boolean mine,
                                                                             @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponses.ok(outreachService.followUps(mine, pageable), "Follow-ups fetched successfully");
    }
}
