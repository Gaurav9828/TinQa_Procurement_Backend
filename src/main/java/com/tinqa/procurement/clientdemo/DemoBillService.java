package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
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
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;

/**
 * The bills of a demo: each night's stay, each meal, each leg of travel and any other cost, with its invoice or ticket.
 * Demo managers keep every bill while the demo is planned or running; attending staff record and change their own while
 * it runs (under "My demos"); a reopened demo is corrected only by a Super Admin. Once the quotation is approved,
 * changing or removing a bill needs a reason, kept on the demo's timeline.
 */
@Service
@RequiredArgsConstructor
public class DemoBillService {

    private final ClientDemoService demoService;
    private final ClientDemoRepository demoRepository;
    private final DemoViews views;
    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final UploadedFileValidator uploadedFileValidator;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final Clock clock;

    /** Who is acting: a demo manager (admin screens) or one of the attending staff (My demos). */
    public enum Acting { MANAGER, ATTENDEE }

    @Transactional
    public ClientDemoDTOs.Detail add(Acting acting, Long demoId, ClientDemoDTOs.ExpenseRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lockFor(acting, demoId, actor);
        requireMayWrite(acting, demo, null, actor);
        if (demo.getExpenses().size() >= ClientDemoDTOs.MAX_EXPENSES) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_NOT_EDITABLE, demo.reference() + " already has "
                    + ClientDemoDTOs.MAX_EXPENSES + " bills.");
        }
        Instant now = clock.instant();
        DemoExpense expense = DemoExpense.builder().demo(demo).addedBy(actor.getId()).addedAt(now).build();
        DemoBills.apply(demo, expense, request);
        demo.getExpenses().add(expense);
        demo.setUpdatedAt(now);
        // The new bill's id is in the response
        demoRepository.saveAndFlush(demo);
        demoService.event(demo, DemoEventType.EXPENSE_ADDED, actor, DemoBills.describe(expense), null);
        return views.detail(demo, actor);
    }

    @Transactional
    public ClientDemoDTOs.Detail update(Acting acting, Long demoId, Long expenseId, ClientDemoDTOs.ExpenseRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lockFor(acting, demoId, actor);
        DemoExpense expense = expenseOf(demo, expenseId);
        requireMayWrite(acting, demo, expense, actor);
        String reason = changeReason(demo, request.getChangeReason());
        String before = DemoBills.describe(expense);
        DemoBills.apply(demo, expense, request);
        Instant now = clock.instant();
        expense.setUpdatedBy(actor.getId());
        expense.setUpdatedAt(now);
        demo.setUpdatedAt(now);
        String after = DemoBills.describe(expense);
        demoService.event(demo, DemoEventType.EXPENSE_UPDATED, actor, before.equals(after) ? after : before + " → " + after, reason);
        return views.detail(demo, actor);
    }

    @Transactional
    public ClientDemoDTOs.Detail remove(Acting acting, Long demoId, Long expenseId, String changeReason) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lockFor(acting, demoId, actor);
        DemoExpense expense = expenseOf(demo, expenseId);
        requireMayWrite(acting, demo, expense, actor);
        String reason = changeReason(demo, changeReason);
        demo.getExpenses().remove(expense);
        demo.setUpdatedAt(clock.instant());
        demoService.event(demo, DemoEventType.EXPENSE_REMOVED, actor, DemoBills.describe(expense), reason);
        return views.detail(demo, actor);
    }

    /** Attaches the bill's invoice or ticket (a PDF or a photo); a new one replaces the old. */
    @Transactional
    public ClientDemoDTOs.Detail attachInvoice(Acting acting, Long demoId, Long expenseId, MultipartFile file) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = lockFor(acting, demoId, actor);
        DemoExpense expense = expenseOf(demo, expenseId);
        requireMayWrite(acting, demo, expense, actor);
        UploadedFileValidator.ValidatedFile validated = uploadedFileValidator.validate(file, DocumentType.OTHER);
        DocumentType type = validated.contentType().equals("application/pdf") ? DocumentType.PDF
                : validated.contentType().startsWith("image/") ? DocumentType.IMAGE : null;
        if (type == null) {
            throw new ApiException("An invoice or ticket must be a PDF or a photo (png, jpg, webp).", ErrorCodes.INVALID_FILE,
                    HttpStatus.BAD_REQUEST, java.util.List.of(ApiErrors.error("file", "Upload a PDF or a photo of the bill.")));
        }

        String storageKey = fileStorageService.storeFile(file, "client-demo/invoice");
        deleteFileAfterRollback(storageKey);
        Document document = documentRepository.save(Document.builder()
                .originalFileName(validated.originalFileName())
                .storageKey(storageKey)
                .contentType(validated.contentType())
                .fileSize(file.getSize())
                .uploaderType(currentAccess.has(Permission.DEMO_APPROVE) ? DocumentUploaderType.ADMIN_L2 : DocumentUploaderType.ADMIN_L1)
                .uploadedByUserId(actor.getId())
                .ownerType(DocumentOwnerType.ADMIN)
                .ownerId(actor.getId())
                .referenceType(DocumentReferenceType.CLIENT_DEMO)
                .referenceId(demo.getId())
                .category(DocumentCategory.INVOICE)
                .purpose(DocumentPurpose.INVOICE)
                .type(type)
                .stage(DocumentStage.GENERAL)
                // Checked by whoever approves the demo's completion, not by a document approver
                .status(DocumentStatus.ACTIVE)
                .build());
        Instant now = clock.instant();
        boolean replaced = expense.getDocumentId() != null;
        expense.setDocumentId(document.getId());
        expense.setUpdatedBy(actor.getId());
        expense.setUpdatedAt(now);
        demo.setUpdatedAt(now);
        demoService.event(demo, DemoEventType.INVOICE_ATTACHED, actor, (replaced ? "Replaced the invoice of " : "Invoice attached to ")
                + DemoBills.describe(expense) + ": " + validated.originalFileName(), null);
        return views.detail(demo, actor);
    }

    @Transactional(readOnly = true)
    public DocumentDownload invoice(Acting acting, Long demoId, Long expenseId) {
        User actor = currentUserProvider.getCurrentUser();
        ClientDemo demo = acting == Acting.ATTENDEE ? demoService.attended(demoId, actor) : demoService.find(demoId);
        DemoExpense expense = expenseOf(demo, expenseId);
        Document document = expense.getDocumentId() == null ? null : documentRepository.findById(expense.getDocumentId()).orElse(null);
        if (document == null) {
            throw new ResourceNotFoundException("This bill has no invoice attached.");
        }
        return new DocumentDownload(fileStorageService.loadFileAsResource(document.getStorageKey()),
                document.getContentType(), document.getOriginalFileName());
    }

    // ------------------------------------------------------------------ rules

    private ClientDemo lockFor(Acting acting, Long demoId, User actor) {
        if (acting == Acting.ATTENDEE) {
            // Not found unless the caller attends it
            demoService.attended(demoId, actor);
        }
        return demoService.lock(demoId);
    }

    private void requireMayWrite(Acting acting, ClientDemo demo, DemoExpense expense, User actor) {
        if (acting == Acting.MANAGER) {
            if (!views.managesBills(demo)) {
                throw ClientDemoService.notEditable(demo);
            }
            return;
        }
        if (demo.getStatus() != DemoStatus.APPROVED) {
            throw ApiErrors.conflict(ErrorCodes.DEMO_NOT_EDITABLE, demo.reference() + " is " + demo.getStatus().label()
                    .toLowerCase(Locale.ROOT) + ". Attending staff record bills while an approved demo runs.");
        }
        if (!views.recordsOwnBill(demo, expense, actor.getId(), views.staffUserIds(demo))) {
            throw ApiErrors.forbidden(ErrorCodes.DEMO_NOT_ATTENDING, "You can only change the bills you added yourself.");
        }
    }

    // Once the quotation is approved, every change to a recorded bill says why
    private static String changeReason(ClientDemo demo, String requested) {
        String reason = OptionalText.blankToNull(requested);
        if (reason == null && demo.getStatus() != DemoStatus.DRAFT) {
            throw ApiErrors.fieldError("changeReason", "Give a reason for changing a recorded bill.");
        }
        return reason;
    }

    private static DemoExpense expenseOf(ClientDemo demo, Long expenseId) {
        return demo.getExpenses().stream().filter(expense -> expense.getId().equals(expenseId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found with id: " + expenseId));
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
}
