package com.tinqa.procurement.outreach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.clientdemo.ClientCategory;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.util.BusinessTime;
import com.tinqa.procurement.common.util.Pages;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.common.validation.PhoneNumbers;
import com.tinqa.procurement.document.constant.*;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.document.entity.Document;
import com.tinqa.procurement.document.repository.DocumentRepository;
import com.tinqa.procurement.document.service.FileStorageService;
import com.tinqa.procurement.document.service.UploadedFileValidator;
import com.tinqa.procurement.employee.service.StaffNames;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Client outreach: the businesses we pitch to and every contact with them. Anyone with OUTREACH_LOG adds businesses
 * and logs their own contacts; only the person who logged a contact edits it or its proofs.
 */
@Service
@RequiredArgsConstructor
public class OutreachService {

    static final int MAX_PROOFS = 5;
    static final long MAX_PROOF_BYTES = 5L * 1024 * 1024;
    private static final Set<String> PROOF_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "application/pdf");
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int MAX_SUMMARY_DAYS = 366;

    private final OutreachBusinessRepository businessRepository;
    private final OutreachContactRepository contactRepository;
    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final UploadedFileValidator uploadedFileValidator;
    private final StaffNames staffNames;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ------------------------------------------------------------------ businesses

    /** Businesses, latest contact first; search matches the name, phone or email. */
    @Transactional(readOnly = true)
    public Page<OutreachDTOs.Business> businesses(String search, ClientCategory type, Interest interest, boolean followUpDue,
                                                  Pageable pageable) {
        Specification<OutreachBusiness> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            String term = OptionalText.lowerCaseOrNull(search);
            if (term != null) {
                String pattern = "%" + likeEscape(term) + "%";
                String digits = term.replaceAll("[\\s()+-]", "");
                List<Predicate> any = new ArrayList<>(List.of(
                        cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("email")), pattern, '\\')));
                if (!digits.isEmpty()) {
                    any.add(cb.like(root.get("phone"), "%" + likeEscape(digits) + "%", '\\'));
                }
                where.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (type != null) {
                where.add(cb.equal(root.get("businessType"), type));
            }
            if (interest != null) {
                where.add(cb.equal(root.get("latestInterest"), interest));
            }
            if (followUpDue) {
                where.add(cb.lessThan(root.get("nextFollowUpAt"), endOfToday()));
            }
            // Latest contact first, never-contacted last (matches idx_outreach_business_last_contact). Spring's Sort
            // can't say NULLS LAST in criteria queries, so the order is set here; the count query drops it
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
                query.orderBy(hcb.desc(root.get("lastContactAt"), false), hcb.desc(root.get("id")));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<OutreachBusiness> page = businessRepository.findAll(spec, Pages.capped(pageable));
        Map<Long, String> names = staffNames.of(page.getContent().stream()
                .flatMap(b -> Stream.of(b.getCreatedBy(), b.getLastContactBy())).toList());
        return page.map(business -> view(business, names));
    }

    @Transactional(readOnly = true)
    public OutreachDTOs.Business business(Long id) {
        OutreachBusiness business = findBusiness(id);
        return view(business, staffNames.of(Stream.of(business.getCreatedBy(), business.getLastContactBy()).toList()));
    }

    @Transactional
    public OutreachDTOs.Business createBusiness(OutreachDTOs.BusinessRequest request) {
        Long actor = currentUserProvider.getCurrentUserId();
        Instant now = clock.instant();
        OutreachBusiness business = OutreachBusiness.builder().createdBy(actor).createdAt(now).build();
        apply(business, request, -1L);
        business.setUpdatedAt(now);
        return saveBusiness(business);
    }

    @Transactional
    public OutreachDTOs.Business updateBusiness(Long id, OutreachDTOs.BusinessRequest request) {
        OutreachBusiness business = businessRepository.findByIdForUpdate(id).orElseThrow(() -> businessNotFound(id));
        apply(business, request, id);
        business.setUpdatedAt(clock.instant());
        return saveBusiness(business);
    }

    private OutreachDTOs.Business saveBusiness(OutreachBusiness business) {
        OutreachBusiness saved;
        try {
            saved = businessRepository.saveAndFlush(business);
        } catch (DataIntegrityViolationException exception) {
            // Added by someone else between the check and the insert
            OutreachBusiness existing = duplicateOf(business.getPhone(), business.getEmail(),
                    Objects.requireNonNullElse(business.getId(), -1L));
            if (existing != null) {
                throw exists(existing);
            }
            throw exception;
        }
        return view(saved, staffNames.of(Stream.of(saved.getCreatedBy(), saved.getLastContactBy()).toList()));
    }

    private void apply(OutreachBusiness business, OutreachDTOs.BusinessRequest request, Long excludeId) {
        String phone = businessPhone(request.phone());
        String email = OptionalText.lowerCaseOrNull(request.email());
        OutreachBusiness existing = duplicateOf(phone, email, excludeId);
        if (existing != null) {
            throw exists(existing);
        }
        business.setName(request.name().trim());
        business.setBusinessType(request.businessType());
        business.setCity(OptionalText.blankToNull(request.city()));
        business.setAddress(OptionalText.blankToNull(request.address()));
        business.setGoogleMapsUrl(OptionalText.blankToNull(request.googleMapsUrl()));
        business.setPhone(phone);
        business.setEmail(email);
        business.setContactPerson(OptionalText.blankToNull(request.contactPerson()));
        business.setNotes(OptionalText.blankToNull(request.notes()));
    }

    private OutreachBusiness duplicateOf(String phone, String email, Long excludeId) {
        if (phone != null) {
            OutreachBusiness byPhone = businessRepository.findFirstByPhoneAndIdNot(phone, excludeId).orElse(null);
            if (byPhone != null) {
                return byPhone;
            }
        }
        return email == null ? null : businessRepository.findByEmailExcluding(email, excludeId).orElse(null);
    }

    private static BusinessExistsException exists(OutreachBusiness existing) {
        return new BusinessExistsException(existing.getId(), existing.getName() + " is already on the outreach list with this "
                + "phone number or email. Open it to see who contacted them and log your contact there.");
    }

    /** Indian numbers as their 10 digits; other numbers as +<country code><number>. */
    static String businessPhone(String phone) {
        String normalized = PhoneNumbers.normalize(OptionalText.blankToNull(phone));
        if (normalized != null && normalized.matches("^\\+91[6-9]\\d{9}$")) {
            return normalized.substring(3);
        }
        return normalized;
    }

    // ------------------------------------------------------------------ contacts

    /** A business's contacts, newest first. */
    @Transactional(readOnly = true)
    public Page<OutreachDTOs.Contact> contactsOf(Long businessId, Pageable pageable) {
        findBusiness(businessId);
        return views(contactRepository.findByBusiness(businessId, Pages.capped(pageable)));
    }

    /** Every contact, newest first, filtered by date (company days, both inclusive), who logged it, channel or interest. */
    @Transactional(readOnly = true)
    public Page<OutreachDTOs.Contact> contacts(LocalDate from, LocalDate to, Long loggedBy, ContactChannel channel, Interest interest,
                                               boolean mine, Pageable pageable) {
        Long logger = mine ? currentUserProvider.getCurrentUserId() : loggedBy;
        Specification<OutreachContact> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("contactedAt"), BusinessTime.startOf(from)));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("contactedAt"), BusinessTime.startOf(to.plusDays(1))));
            }
            if (logger != null) {
                where.add(cb.equal(root.get("loggedBy"), logger));
            }
            if (channel != null) {
                where.add(cb.equal(root.get("channel"), channel));
            }
            if (interest != null) {
                where.add(cb.equal(root.get("interest"), interest));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return views(contactRepository.findAll(spec, Pages.capped(pageable,
                Sort.by(Sort.Order.desc("contactedAt"), Sort.Order.desc("id")))));
    }

    /** Follow-ups due by the end of today that no later contact with the business has superseded; oldest first. */
    @Transactional(readOnly = true)
    public Page<OutreachDTOs.Contact> followUps(boolean mine, Pageable pageable) {
        return views(contactRepository.findFollowUpsDue(endOfToday(), mine ? currentUserProvider.getCurrentUserId() : null,
                Pages.capped(pageable)));
    }

    @Transactional
    public OutreachDTOs.Contact logContact(Long businessId, OutreachDTOs.ContactRequest request) {
        Long actor = currentUserProvider.getCurrentUserId();
        // Serializes contacts of one business, so its summary is rewritten in order
        OutreachBusiness business = businessRepository.findByIdForUpdate(businessId).orElseThrow(() -> businessNotFound(businessId));
        Instant now = clock.instant();
        OutreachContact contact = OutreachContact.builder().businessId(business.getId()).loggedBy(actor).createdAt(now).build();
        apply(contact, request, now);
        contact.setUpdatedAt(now);
        OutreachContact saved = contactRepository.saveAndFlush(contact);
        businessRepository.refreshSummary(business.getId());
        return view(saved, business.getName(), staffNames.of(List.of(actor)), List.of(), actor);
    }

    @Transactional
    public OutreachDTOs.Contact editContact(Long contactId, OutreachDTOs.ContactRequest request) {
        Long actor = currentUserProvider.getCurrentUserId();
        OutreachContact found = contactRepository.findById(contactId).orElseThrow(() -> contactNotFound(contactId));
        requireOwn(found, actor);
        OutreachBusiness business = businessRepository.findByIdForUpdate(found.getBusinessId())
                .orElseThrow(() -> businessNotFound(found.getBusinessId()));
        OutreachContact contact = contactRepository.findByIdForUpdate(contactId).orElseThrow(() -> contactNotFound(contactId));
        Instant previousFollowUp = contact.getFollowUpAt();
        Instant now = clock.instant();
        apply(contact, request, now);
        if (!Objects.equals(previousFollowUp, contact.getFollowUpAt())) {
            // A new follow-up date gets its own reminder
            contact.setFollowUpRemindedAt(null);
        }
        contact.setUpdatedAt(now);
        OutreachContact saved = contactRepository.saveAndFlush(contact);
        businessRepository.refreshSummary(business.getId());
        return view(saved, business.getName(), staffNames.of(List.of(actor)), proofsOf(List.of(contactId))
                .getOrDefault(contactId, List.of()), actor);
    }

    private void apply(OutreachContact contact, OutreachDTOs.ContactRequest request, Instant now) {
        Instant contactedAt = request.contactedAt() == null ? now : request.contactedAt().toInstant().truncatedTo(ChronoUnit.SECONDS);
        if (contactedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw ApiErrors.fieldError("contactedAt", "A contact can't be logged for a time in the future.");
        }
        Instant followUpAt = null;
        if (request.nextAction() != NextAction.NONE) {
            if (request.followUpAt() == null) {
                throw ApiErrors.fieldError("followUpAt", "Choose when to follow up (for example, when to call again or the demo date).");
            }
            followUpAt = request.followUpAt().toInstant().truncatedTo(ChronoUnit.SECONDS);
            if (!followUpAt.isAfter(contactedAt)) {
                throw ApiErrors.fieldError("followUpAt", "The follow-up must be after the contact.");
            }
        }
        contact.setContactedAt(contactedAt);
        contact.setChannel(request.channel());
        contact.setContactPerson(OptionalText.blankToNull(request.contactPerson()));
        contact.setContactPhone(PhoneNumbers.normalize(OptionalText.blankToNull(request.contactPhone())));
        contact.setSummary(request.summary().trim());
        contact.setResponse(OptionalText.blankToNull(request.response()));
        contact.setRating(request.rating());
        contact.setInterest(request.interest());
        contact.setNextAction(request.nextAction());
        contact.setFollowUpAt(followUpAt);
    }

    // ------------------------------------------------------------------ proofs

    @Transactional
    public OutreachDTOs.Proof addProof(Long contactId, MultipartFile file) {
        Long actor = currentUserProvider.getCurrentUserId();
        OutreachContact contact = contactRepository.findByIdForUpdate(contactId).orElseThrow(() -> contactNotFound(contactId));
        requireOwn(contact, actor);
        if (documentRepository.countByReferenceTypeAndReferenceIdAndStatus(DocumentReferenceType.OUTREACH_CONTACT, contactId,
                DocumentStatus.ACTIVE) >= MAX_PROOFS) {
            throw ApiErrors.conflict(ErrorCodes.OUTREACH_PROOF_LIMIT, "A contact holds at most " + MAX_PROOFS
                    + " proofs. Remove one to add another.");
        }
        if (file != null && file.getSize() > MAX_PROOF_BYTES) {
            throw invalidProof("A proof can be at most 5 MB.");
        }
        UploadedFileValidator.ValidatedFile validated = uploadedFileValidator.validate(file, DocumentType.OTHER);
        if (!PROOF_TYPES.contains(validated.contentType())) {
            throw invalidProof("A proof must be a screenshot or photo (png, jpg, webp) or a PDF.");
        }
        String storageKey = fileStorageService.storeFile(file, "outreach/proof");
        deleteFileAfterRollback(storageKey);
        Document document = documentRepository.save(Document.builder()
                .originalFileName(validated.originalFileName())
                .storageKey(storageKey)
                .contentType(validated.contentType())
                .fileSize(file.getSize())
                .uploaderType(DocumentUploaderType.ADMIN_L1)
                .uploadedByUserId(actor)
                .ownerType(DocumentOwnerType.ADMIN)
                .ownerId(actor)
                .referenceType(DocumentReferenceType.OUTREACH_CONTACT)
                .referenceId(contactId)
                .category(DocumentCategory.OTHER)
                .purpose(DocumentPurpose.OTHER)
                .type(validated.contentType().equals("application/pdf") ? DocumentType.PDF : DocumentType.IMAGE)
                .stage(DocumentStage.GENERAL)
                .status(DocumentStatus.ACTIVE)
                .build());
        return proof(document);
    }

    @Transactional(readOnly = true)
    public DocumentDownload proofFile(Long contactId, Long proofId) {
        Document document = activeProof(contactId, proofId);
        return new DocumentDownload(fileStorageService.loadFileAsResource(document.getStorageKey()), document.getContentType(),
                document.getOriginalFileName());
    }

    @Transactional
    public void removeProof(Long contactId, Long proofId) {
        OutreachContact contact = contactRepository.findById(contactId).orElseThrow(() -> contactNotFound(contactId));
        requireOwn(contact, currentUserProvider.getCurrentUserId());
        Document document = activeProof(contactId, proofId);
        document.setStatus(DocumentStatus.DELETED);
        documentRepository.save(document);
        String storageKey = document.getStorageKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileStorageService.deleteFile(storageKey);
            }
        });
    }

    private Document activeProof(Long contactId, Long proofId) {
        return documentRepository.findByIdAndReferenceTypeAndReferenceIdAndStatus(proofId, DocumentReferenceType.OUTREACH_CONTACT,
                contactId, DocumentStatus.ACTIVE).orElseThrow(() -> new ResourceNotFoundException("Proof not found with id: " + proofId));
    }

    private static ApiException invalidProof(String message) {
        return new ApiException(message, ErrorCodes.INVALID_FILE, HttpStatus.BAD_REQUEST, List.of(ApiErrors.error("file", message)));
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

    // ------------------------------------------------------------------ summary

    /** The outreach dashboard for company days from..to (both inclusive; the last 30 days by default), in one statement. */
    @Transactional(readOnly = true)
    public OutreachDTOs.Summary summary(LocalDate from, LocalDate to) {
        LocalDate last = to != null ? to : BusinessTime.today(clock);
        LocalDate first = from != null ? from : last.minusDays(29);
        if (first.isAfter(last)) {
            throw ApiErrors.fieldError("from", "From must be on or before to.");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_SUMMARY_DAYS) {
            throw ApiErrors.fieldError("from", "Choose at most " + MAX_SUMMARY_DAYS + " days.");
        }
        String json = businessRepository.summary(BusinessTime.startOf(first), BusinessTime.startOf(last.plusDays(1)), endOfToday(),
                BusinessTime.ZONE.getId());
        try {
            return objectMapper.readValue(json, OutreachDTOs.Summary.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unreadable outreach summary", exception);
        }
    }

    // ------------------------------------------------------------------ views

    private Page<OutreachDTOs.Contact> views(Page<OutreachContact> page) {
        List<OutreachContact> contacts = page.getContent();
        Long me = currentUserProvider.getCurrentUserId();
        Map<Long, String> businessNames = businessRepository.findAllById(contacts.stream().map(OutreachContact::getBusinessId)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(OutreachBusiness::getId, OutreachBusiness::getName));
        Map<Long, String> names = staffNames.of(contacts.stream().map(OutreachContact::getLoggedBy).toList());
        Map<Long, List<OutreachDTOs.Proof>> proofs = proofsOf(contacts.stream().map(OutreachContact::getId).toList());
        return page.map(contact -> view(contact, businessNames.get(contact.getBusinessId()), names,
                proofs.getOrDefault(contact.getId(), List.of()), me));
    }

    private Map<Long, List<OutreachDTOs.Proof>> proofsOf(Collection<Long> contactIds) {
        if (contactIds.isEmpty()) {
            return Map.of();
        }
        return documentRepository.findByReferenceTypeAndReferenceIdInAndStatusOrderByIdAsc(DocumentReferenceType.OUTREACH_CONTACT,
                        contactIds, DocumentStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(Document::getReferenceId, Collectors.mapping(OutreachService::proof, Collectors.toList())));
    }

    private static OutreachDTOs.Proof proof(Document document) {
        return new OutreachDTOs.Proof(document.getId(), document.getOriginalFileName(), document.getContentType(),
                document.getFileSize() == null ? 0 : document.getFileSize());
    }

    private static OutreachDTOs.Contact view(OutreachContact contact, String businessName, Map<Long, String> names,
                                             List<OutreachDTOs.Proof> proofs, Long me) {
        return new OutreachDTOs.Contact(contact.getId(), contact.getBusinessId(), businessName,
                BusinessTime.of(contact.getContactedAt()), contact.getChannel(), contact.getContactPerson(), contact.getContactPhone(),
                contact.getSummary(), contact.getResponse(), contact.getRating(), contact.getInterest(), contact.getNextAction(),
                BusinessTime.of(contact.getFollowUpAt()), proofs, contact.getLoggedBy(), names.get(contact.getLoggedBy()),
                BusinessTime.of(contact.getCreatedAt()), BusinessTime.of(contact.getUpdatedAt()),
                contact.getLoggedBy().equals(me));
    }

    private static OutreachDTOs.Business view(OutreachBusiness business, Map<Long, String> names) {
        return new OutreachDTOs.Business(business.getId(), business.getName(), business.getBusinessType(), business.getCity(),
                business.getAddress(), business.getGoogleMapsUrl(), business.getPhone(), business.getEmail(),
                business.getContactPerson(), business.getNotes(), business.getContactCount(), business.getDemoRequestCount(),
                BusinessTime.of(business.getLastContactAt()), names.get(business.getLastContactBy()), business.getLatestInterest(),
                business.getLatestRating(), BusinessTime.of(business.getNextFollowUpAt()), names.get(business.getCreatedBy()),
                BusinessTime.of(business.getCreatedAt()));
    }

    // ------------------------------------------------------------------ helpers

    private static void requireOwn(OutreachContact contact, Long actor) {
        if (!contact.getLoggedBy().equals(actor)) {
            throw ApiErrors.forbidden(ErrorCodes.OUTREACH_NOT_YOURS, "Only the person who logged this contact can change it.");
        }
    }

    private Instant endOfToday() {
        return BusinessTime.startOf(BusinessTime.today(clock).plusDays(1));
    }

    private OutreachBusiness findBusiness(Long id) {
        return businessRepository.findById(id).orElseThrow(() -> businessNotFound(id));
    }

    private static ResourceNotFoundException businessNotFound(Long id) {
        return new ResourceNotFoundException("Business not found with id: " + id);
    }

    private static ResourceNotFoundException contactNotFound(Long id) {
        return new ResourceNotFoundException("Contact not found with id: " + id);
    }

    private static String likeEscape(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
