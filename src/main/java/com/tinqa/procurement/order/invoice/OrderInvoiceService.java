package com.tinqa.procurement.order.invoice;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.document.constant.DocumentCategory;
import com.tinqa.procurement.document.constant.DocumentOwnerType;
import com.tinqa.procurement.document.constant.DocumentPurpose;
import com.tinqa.procurement.document.constant.DocumentReferenceType;
import com.tinqa.procurement.document.constant.DocumentStage;
import com.tinqa.procurement.document.constant.DocumentStatus;
import com.tinqa.procurement.document.constant.DocumentType;
import com.tinqa.procurement.document.constant.DocumentUploaderType;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.document.entity.Document;
import com.tinqa.procurement.document.repository.DocumentRepository;
import com.tinqa.procurement.document.service.FileStorageService;
import com.tinqa.procurement.document.service.UploadedFileValidator;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.order.tax.QuotationTax;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The supplier's invoice for an order. Staff upload the PDF the supplier sent before marking the order delivered; its text
 * is read and checked against the quotation (total, GST, the supplier's GSTIN). A matching invoice lets the order be
 * delivered; one that doesn't match, or can't be read, needs a Level 2 approver to accept it with a reason.
 */
@Service
@RequiredArgsConstructor
public class OrderInvoiceService {

    private static final Set<OrderStatus> UPLOADABLE = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED);

    private final OrderRepository orderRepository;
    private final OrderInvoiceRepository invoiceRepository;
    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final UploadedFileValidator uploadedFileValidator;
    private final InvoicePdfReader pdfReader;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final Clock clock;

    @Transactional
    public OrderInvoiceDTOs.Response upload(Long orderId, MultipartFile file) {
        User uploader = currentUserProvider.getCurrentUser();
        // Locked, so a delivery being recorded at the same moment sees either the old invoice or this one
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> orderNotFound(orderId));
        if (!UPLOADABLE.contains(order.getOrderStatus())) {
            throw ApiErrors.conflict(ErrorCodes.ORDER_INVOICE_NOT_ALLOWED, "Order " + order.getOrderNumber() + " is "
                    + order.getOrderStatus().label() + ". The supplier's invoice is uploaded while an order is Confirmed or Shipped, "
                    + "before it is marked delivered.");
        }
        UploadedFileValidator.ValidatedFile validated = uploadedFileValidator.validate(file, DocumentType.PDF);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw ApiErrors.fieldError("file", "The file could not be read.");
        }

        String storageKey = fileStorageService.storeFile(file, "order/invoice");
        deleteFileAfterRollback(storageKey);
        Document document = documentRepository.save(Document.builder()
                .originalFileName(validated.originalFileName())
                .storageKey(storageKey)
                .contentType(validated.contentType())
                .fileSize(file.getSize())
                .uploaderType(currentAccess.has(Permission.PROCUREMENT_ORDER_APPROVE) ? DocumentUploaderType.ADMIN_L2 : DocumentUploaderType.ADMIN_L1)
                .uploadedByUserId(uploader.getId())
                .ownerType(DocumentOwnerType.SUPPLIER)
                .ownerId(order.getSupplier().getId())
                .referenceType(DocumentReferenceType.ORDER)
                .referenceId(order.getId())
                .category(DocumentCategory.INVOICE)
                .purpose(DocumentPurpose.INVOICE)
                .type(DocumentType.PDF)
                .stage(DocumentStage.DELIVERY)
                // It is checked against the quotation instead of waiting for a document approver
                .status(DocumentStatus.ACTIVE)
                .build());

        InvoiceVerifier.Expected expected = expectedFor(order);
        InvoiceFigures figures = InvoiceFigureExtractor.extract(pdfReader.readText(content));
        InvoiceVerifier.Result result = InvoiceVerifier.verify(expected, figures);
        OrderInvoice saved = invoiceRepository.save(OrderInvoice.builder()
                .orderId(order.getId())
                .documentId(document.getId())
                .invoiceNumber(figures.invoiceNumber())
                .status(result.status())
                .expectedTotal(expected.grandTotal())
                .foundTotal(figures.grandTotal())
                .checks(result.checks())
                .uploadedBy(uploader.getId())
                .uploadedAt(clock.instant())
                .build());

        if (result.status() != InvoiceVerificationStatus.MATCHED) {
            notificationService.createForPermission(Permission.PROCUREMENT_ORDER_APPROVE, uploader.getId(), "Supplier invoice needs a check",
                    "The invoice uploaded for order " + order.getOrderNumber() + " (" + order.getSupplier().getName() + ") "
                            + describe(saved) + ". The order can only be marked delivered once an approver accepts it or a correct invoice is uploaded.",
                    NotificationLinks.ORDERS);
        }
        return toResponse(saved, document);
    }

    @Transactional(readOnly = true)
    public Optional<OrderInvoiceDTOs.Response> current(Long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw orderNotFound(orderId);
        }
        return invoiceRepository.findFirstByOrderIdOrderByIdDesc(orderId)
                .map(invoice -> toResponse(invoice, documentRepository.findById(invoice.getDocumentId()).orElse(null)));
    }

    @Transactional(readOnly = true)
    public DocumentDownload download(Long orderId) {
        OrderInvoice invoice = invoiceRepository.findFirstByOrderIdOrderByIdDesc(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("No invoice has been uploaded for order id: " + orderId));
        Document document = documentRepository.findById(invoice.getDocumentId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice file not found for order id: " + orderId));
        return new DocumentDownload(fileStorageService.loadFileAsResource(document.getStorageKey()),
                document.getContentType(), document.getOriginalFileName());
    }

    /**
     * Checks the order may be marked delivered as far as its invoice goes, and records an approver's override. The
     * caller has locked the order. Returns the note for the status history when an override was needed, else null.
     */
    @Transactional
    public String acceptForDelivery(Order order, String overrideReason, User actor) {
        OrderInvoice invoice = invoiceRepository.findFirstByOrderIdOrderByIdDesc(order.getId())
                .orElseThrow(() -> ApiErrors.conflict(ErrorCodes.ORDER_INVOICE_REQUIRED, "Upload the supplier's invoice (PDF) for order "
                        + order.getOrderNumber() + " before marking it delivered. It is checked against the quotation."));
        if (invoice.getStatus() == InvoiceVerificationStatus.MATCHED) {
            return null;
        }
        if (!currentAccess.has(Permission.PROCUREMENT_ORDER_APPROVE)) {
            throw ApiErrors.conflict(ErrorCodes.ORDER_INVOICE_MISMATCH, "The supplier's invoice for order " + order.getOrderNumber() + " "
                    + describe(invoice) + ". Upload a correct invoice, or ask a Level 2 approver to accept it with a reason.");
        }
        String reason = OptionalText.blankToNull(overrideReason);
        if (reason == null) {
            throw ApiErrors.fieldError("invoiceOverrideReason", "The supplier's invoice " + describe(invoice)
                    + ". Give a reason to accept it and mark the order delivered.");
        }
        invoice.setOverrideBy(actor.getId());
        invoice.setOverrideAt(clock.instant());
        invoice.setOverrideReason(reason);
        invoiceRepository.save(invoice);
        return "Invoice accepted although it " + (invoice.getStatus() == InvoiceVerificationStatus.UNVERIFIED
                ? "could not be checked" : "did not match the quotation") + ": " + reason;
    }

    private static InvoiceVerifier.Expected expectedFor(Order order) {
        Map<String, Object> tax = order.getTaxBreakup();
        boolean taxQuoted = QuotationTax.present(tax);
        return new InvoiceVerifier.Expected(
                QuotationTax.grandTotal(tax, order.getTotalPrice()),
                taxQuoted,
                order.getTotalPrice(),
                QuotationTax.amountOf(taxQuoted ? tax : null, QuotationTax.CGST),
                QuotationTax.amountOf(taxQuoted ? tax : null, QuotationTax.SGST),
                QuotationTax.amountOf(taxQuoted ? tax : null, QuotationTax.IGST),
                QuotationTax.amountOf(taxQuoted ? tax : null, QuotationTax.CESS),
                OptionalText.blankToNull(order.getSupplier().getGstin()));
    }

    // "doesn't match the quotation (Invoice total, IGST)" / "could not be checked automatically (...)"
    private static String describe(OrderInvoice invoice) {
        String failed = invoice.getChecks().stream().filter(check -> !check.isOk()).map(InvoiceCheck::getLabel)
                .collect(Collectors.joining(", "));
        return (invoice.getStatus() == InvoiceVerificationStatus.UNVERIFIED ? "could not be checked automatically" : "doesn't match the quotation")
                + (failed.isEmpty() ? "" : " (" + failed + ")");
    }

    private OrderInvoiceDTOs.Response toResponse(OrderInvoice invoice, Document document) {
        Map<Long, String> names = userRepository.findAllById(Stream.of(invoice.getUploadedBy(), invoice.getOverrideBy())
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return OrderInvoiceDTOs.Response.builder()
                .id(invoice.getId())
                .orderId(invoice.getOrderId())
                .fileName(document != null ? document.getOriginalFileName() : null)
                .invoiceNumber(invoice.getInvoiceNumber())
                .status(invoice.getStatus())
                .expectedTotal(invoice.getExpectedTotal())
                .foundTotal(invoice.getFoundTotal())
                .checks(invoice.getChecks())
                .uploadedByUsername(names.get(invoice.getUploadedBy()))
                .uploadedAt(invoice.getUploadedAt())
                .overrideByUsername(invoice.getOverrideBy() != null ? names.get(invoice.getOverrideBy()) : null)
                .overrideAt(invoice.getOverrideAt())
                .overrideReason(invoice.getOverrideReason())
                .matched(invoice.getStatus() == InvoiceVerificationStatus.MATCHED)
                .build();
    }

    private void deleteFileAfterRollback(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorageService.deleteFile(storageKey);
                }
            }
        });
    }

    private static ResourceNotFoundException orderNotFound(Long orderId) {
        return new ResourceNotFoundException("Order not found with id: " + orderId);
    }
}
