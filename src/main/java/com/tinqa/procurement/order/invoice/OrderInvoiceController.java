package com.tinqa.procurement.order.invoice;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/** The supplier's invoice of an order: uploaded by whoever records deliveries, readable by everyone who sees orders. */
@RestController
@RequestMapping("/v1/orders/{orderId}/invoice")
@RequiredArgsConstructor
public class OrderInvoiceController {

    private final OrderInvoiceService invoiceService;

    @RequiresPermission(Permission.PROCUREMENT_ORDER_UPDATE_STATUS)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<OrderInvoiceDTOs.Response>> upload(@PathVariable Long orderId,
                                                                         @RequestPart("file") MultipartFile file) {
        OrderInvoiceDTOs.Response invoice = invoiceService.upload(orderId, file);
        return ApiResponses.ok(invoice, invoice.isMatched()
                ? "Invoice uploaded and matches the quotation"
                : "Invoice uploaded, but it needs a check before the order can be delivered");
    }

    /** The invoice that counts (the latest one), or null when none was uploaded yet. */
    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<OrderInvoiceDTOs.Response>> current(@PathVariable Long orderId) {
        return ApiResponses.ok(invoiceService.current(orderId).orElse(null), "Invoice retrieved successfully");
    }

    // Always served as an attachment with the stored (validated) content type
    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/file")
    public ResponseEntity<Resource> download(@PathVariable Long orderId) {
        DocumentDownload download = invoiceService.download(orderId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }
}
