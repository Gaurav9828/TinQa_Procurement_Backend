package com.tinqa.procurement.productapproval.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs;
import com.tinqa.procurement.productapproval.entity.ProductApprovalRequest;
import com.tinqa.procurement.productapproval.enums.ProductApprovalRequestType;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import com.tinqa.procurement.productapproval.repository.ProductApprovalRequestRepository;
import com.tinqa.procurement.productapproval.service.ProductApprovalService;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;
import com.tinqa.procurement.stock.service.ItemStockMovementService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ProductApprovalServiceImpl implements ProductApprovalService {

    static final int MAX_PRODUCT_IDS = 200;
    static final int MAX_PROPOSED_CHANGES_BYTES = 1_000_000;
    // Ecommerce reserves a product's component stock under this source / "product:<id>" reference
    static final String STOCK_SOURCE = "ECOMMERCE_PRODUCT";

    private final ProductApprovalRequestRepository repository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationService notificationService;
    private final ItemStockMovementService itemStockMovementService;
    private final ItemRepository itemRepository;

    // Maker-checker: the Admin L2 who submitted a product cannot also decide it
    @Value("${tinqa.product-approval.allow-self-decision:false}")
    private boolean allowSelfDecision;

    @Override
    @Transactional
    public ProductApprovalDTOs.Response submit(ProductApprovalDTOs.SubmitRequest request) {
        User requester = currentUserProvider.getCurrentUser();
        Long productId = request.getEcommerceProductId();

        if (request.getRequestType() == ProductApprovalRequestType.UPDATE) {
            return submitUpdate(request, requester);
        }
        if (request.getRequestType() != null) {
            throw fieldError("requestType", "requestType can only be UPDATE; omit it for a new product");
        }

        repository.lockProduct(productId);
        Optional<ProductApprovalRequest> latest = repository.findFirstByEcommerceProductIdOrderByRequestedAtDescIdDesc(productId);
        if (latest.isPresent() && latest.get().getStatus() == ProductApprovalStatus.PENDING) {
            throw pendingConflict();
        }
        // Once approved (by any request), a product only changes through UPDATE requests
        if (latest.map(r -> r.getStatus() == ProductApprovalStatus.APPROVED
                        || r.getRequestType() == ProductApprovalRequestType.UPDATE).orElse(false)
                || repository.existsByEcommerceProductIdAndStatus(productId, ProductApprovalStatus.APPROVED)) {
            throw error(HttpStatus.CONFLICT, "PRODUCT_ALREADY_APPROVED",
                    "This product is already approved. Submit your edits as changes for approval instead.");
        }
        ProductApprovalRequestType type = latest
                .map(r -> switch (r.getStatus()) {
                    case CANCELLED -> throw error(HttpStatus.CONFLICT, "PRODUCT_APPROVAL_CANCELLED",
                            "This product was cancelled and can't be resubmitted.");
                    default -> ProductApprovalRequestType.RESUBMISSION;
                })
                .orElse(ProductApprovalRequestType.CREATE);

        if (type == ProductApprovalRequestType.RESUBMISSION) {
            // Rejection gave the components back; take what the resubmitted product needs again
            reserveComponents(request);
        }

        ProductApprovalRequest saved;
        try {
            saved = repository.saveAndFlush(ProductApprovalRequest.builder()
                    .ecommerceProductId(productId)
                    .productTitle(request.getProductTitle().trim())
                    .requestType(type)
                    .status(ProductApprovalStatus.PENDING)
                    .snapshot(request.getSnapshot())
                    .requestedBy(requester.getId())
                    .requestedAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build());
        } catch (DataIntegrityViolationException exception) {
            // Partial unique index: another PENDING request for this product won the race
            throw pendingConflict();
        }

        notificationService.createForRole(Role.ADMIN_L2, requester.getId(), "Product awaiting approval",
                saved.getProductTitle() + " (#" + productId + ") was "
                        + (type == ProductApprovalRequestType.RESUBMISSION ? "resubmitted" : "submitted")
                        + " by " + requester.getUsername() + ".");
        return toResponses(List.of(saved)).getFirst();
    }

    /**
     * A change to an approved (live) product. Nothing is written to Ecommerce until Admin L2 approves.
     */
    private ProductApprovalDTOs.Response submitUpdate(ProductApprovalDTOs.SubmitRequest request, User requester) {
        Long productId = request.getEcommerceProductId();
        validateUpdateFields(request);

        repository.lockProduct(productId);
        repository.findFirstByEcommerceProductIdOrderByRequestedAtDescIdDesc(productId).ifPresent(latest -> {
            boolean update = latest.getRequestType() == ProductApprovalRequestType.UPDATE;
            switch (latest.getStatus()) {
                case PENDING -> throw pendingConflict();
                case APPROVED -> {
                    if (update && latest.getAppliedAt() == null) {
                        throw error(HttpStatus.CONFLICT, "PRODUCT_UPDATE_NOT_APPLIED",
                                "Approved changes for this product have not been applied yet.");
                    }
                }
                case REJECTED -> {
                    if (!update) {
                        throw error(HttpStatus.CONFLICT, "PRODUCT_NOT_APPROVED",
                                "This product isn't approved yet — edit it and resubmit it for approval.");
                    }
                }
                case CANCELLED -> throw error(HttpStatus.CONFLICT, "PRODUCT_APPROVAL_CANCELLED",
                        "This product was cancelled and can't be changed.");
            }
            // no request at all = product from before the approval workflow, treated as approved
        });

        ProductApprovalRequest saved;
        try {
            saved = repository.saveAndFlush(ProductApprovalRequest.builder()
                    .ecommerceProductId(productId)
                    .productTitle(request.getProductTitle().trim())
                    .requestType(ProductApprovalRequestType.UPDATE)
                    .status(ProductApprovalStatus.PENDING)
                    .snapshot(request.getSnapshot())
                    .proposedChanges(request.getProposedChanges())
                    .previousSnapshot(request.getPreviousSnapshot())
                    .changeSummary(request.getChangeSummary())
                    .requestedBy(requester.getId())
                    .requestedAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build());
        } catch (DataIntegrityViolationException exception) {
            throw pendingConflict();
        }

        int changes = saved.getChangeSummary().size();
        notificationService.createForRole(Role.ADMIN_L2, requester.getId(), "Product update awaiting approval",
                saved.getProductTitle() + " (#" + productId + "): " + changes + " change" + (changes == 1 ? "" : "s")
                        + " requested by " + requester.getUsername() + ".");
        return toResponses(List.of(saved)).getFirst();
    }

    private void validateUpdateFields(ProductApprovalDTOs.SubmitRequest request) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        JsonNode proposed = request.getProposedChanges();
        if (proposed == null || proposed.isNull()) {
            errors.add(new ApiResponse.ApiErrorItem("proposedChanges", "Proposed changes are required"));
        } else if (!proposed.isObject()) {
            errors.add(new ApiResponse.ApiErrorItem("proposedChanges", "Proposed changes must be a JSON object"));
        } else if (proposed.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PROPOSED_CHANGES_BYTES) {
            errors.add(new ApiResponse.ApiErrorItem("proposedChanges", "Proposed changes cannot exceed 1 MB"));
        }
        if (request.getPreviousSnapshot() == null) {
            errors.add(new ApiResponse.ApiErrorItem("previousSnapshot", "Previous snapshot is required"));
        }
        if (request.getChangeSummary() == null || request.getChangeSummary().isEmpty()) {
            errors.add(new ApiResponse.ApiErrorItem("changeSummary", "Change summary must list at least one change"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(errors.size() == 1 ? errors.getFirst().getMessage() : "One or more request fields are invalid.",
                    "VALIDATION_FAILED", HttpStatus.BAD_REQUEST, errors);
        }
    }

    @Override
    @Transactional
    public ProductApprovalDTOs.Response markApplied(Long id) {
        User user = currentUserProvider.getCurrentUser();
        // Row lock: concurrent calls apply once; the first appliedAt is kept
        ProductApprovalRequest approval = repository.findByIdForUpdate(id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PRODUCT_APPROVAL_NOT_FOUND",
                        "Product approval request not found with ID: " + id));
        if (approval.getRequestType() != ProductApprovalRequestType.UPDATE || approval.getStatus() != ProductApprovalStatus.APPROVED) {
            throw error(HttpStatus.CONFLICT, "PRODUCT_APPROVAL_NOT_APPLICABLE",
                    "Only approved product changes can be marked as applied.");
        }
        if (approval.getAppliedAt() == null) {
            approval.setAppliedAt(OffsetDateTime.now(ZoneOffset.UTC));
            approval.setAppliedBy(user.getId());
            approval = repository.saveAndFlush(approval);
        }
        return toResponses(List.of(approval)).getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductApprovalDTOs.Response> getByStatus(ProductApprovalStatus status) {
        return toResponses(repository.findByStatusOrderByRequestedAtAscIdAsc(
                status == null ? ProductApprovalStatus.PENDING : status));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductApprovalDTOs.Response> getLatestForProducts(String productIds) {
        return toResponses(repository.findLatestForProducts(parseProductIds(productIds)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductApprovalDTOs.Response> getHistory(Long ecommerceProductId) {
        return toResponses(repository.findByEcommerceProductIdOrderByRequestedAtDescIdDesc(ecommerceProductId));
    }

    @Override
    @Transactional
    public ProductApprovalDTOs.Response decide(Long id, ProductApprovalDTOs.DecisionRequest request) {
        User reviewer = currentUserProvider.getCurrentUser();
        ProductApprovalStatus decision = request.getDecision();
        if (decision == ProductApprovalStatus.PENDING) {
            throw fieldError("decision", "Decision must be APPROVED, REJECTED or CANCELLED");
        }
        String reason = request.getReason() == null ? null : request.getReason().trim();
        if (decision != ProductApprovalStatus.APPROVED && (reason == null || reason.isEmpty())) {
            throw fieldError("reason", "A reason is required to " + (decision == ProductApprovalStatus.REJECTED ? "reject" : "cancel")
                    + " a product");
        }

        // Row lock: of two reviewers deciding at once, the second sees the first one's decision
        ProductApprovalRequest approval = repository.findByIdForUpdate(id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PRODUCT_APPROVAL_NOT_FOUND",
                        "Product approval request not found with ID: " + id));
        boolean update = approval.getRequestType() == ProductApprovalRequestType.UPDATE;
        if (update && decision == ProductApprovalStatus.CANCELLED) {
            throw error(HttpStatus.BAD_REQUEST, "PRODUCT_APPROVAL_INVALID_DECISION", "Product changes can only be approved or rejected.");
        }
        if (approval.getStatus() != ProductApprovalStatus.PENDING) {
            throw error(HttpStatus.CONFLICT, "PRODUCT_APPROVAL_NOT_PENDING",
                    "This request was already " + approval.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (!allowSelfDecision && approval.getRequestedBy().equals(reviewer.getId())) {
            throw error(HttpStatus.FORBIDDEN, "PRODUCT_APPROVAL_SELF_DECISION",
                    "You submitted this product, so another Admin L2 has to review it.");
        }

        approval.setStatus(decision);
        approval.setDecidedBy(reviewer.getId());
        approval.setDecidedAt(OffsetDateTime.now(ZoneOffset.UTC));
        approval.setDecisionReason(decision == ProductApprovalStatus.APPROVED ? emptyToNull(reason) : reason);
        ProductApprovalRequest saved = repository.saveAndFlush(approval);

        // Approved products keep the component stock reserved when they were created; rejected or cancelled
        // ones give it back to the batches it came from (a resubmission reserves it again)
        // (UPDATE requests concern a live product: its stock is untouched whatever the decision)
        String stockNote = "";
        if (decision != ProductApprovalStatus.APPROVED && !update) {
            ItemStockMovementDTOs.MovementResponse released = itemStockMovementService.releaseReference(
                    STOCK_SOURCE, stockReference(saved.getEcommerceProductId()), reviewer.getUsername());
            stockNote = describeReturned(released);
        }

        if (update && !saved.getRequestedBy().equals(reviewer.getId())) {
            String product = saved.getProductTitle() + " (#" + saved.getEcommerceProductId() + ")";
            if (decision == ProductApprovalStatus.APPROVED) {
                notificationService.createForUser(saved.getRequestedBy(), "Product changes approved",
                        "Your changes to " + product + " were approved by " + reviewer.getUsername() + ".");
            } else {
                notificationService.createForUser(saved.getRequestedBy(), "Product changes rejected",
                        "Your changes to " + product + " were rejected by " + reviewer.getUsername() + ". Reason: " + reason
                                + ". The product is unchanged; you can submit new changes.");
            }
        } else if (!saved.getRequestedBy().equals(reviewer.getId())) {
            String product = saved.getProductTitle() + " (#" + saved.getEcommerceProductId() + ")";
            switch (decision) {
                case APPROVED -> notificationService.createForUser(saved.getRequestedBy(), "Product approved",
                        product + " was approved by " + reviewer.getUsername() + ".");
                case REJECTED -> notificationService.createForUser(saved.getRequestedBy(), "Product rejected",
                        product + " was rejected by " + reviewer.getUsername() + ". Reason: " + reason
                                + ". Edit the product and submit it again." + stockNote);
                case CANCELLED -> notificationService.createForUser(saved.getRequestedBy(), "Product cancelled",
                        product + " was cancelled by " + reviewer.getUsername() + ". Reason: " + reason
                                + ". It will stay inactive and can't be resubmitted." + stockNote);
                default -> {
                }
            }
        }
        return toResponses(List.of(saved)).getFirst();
    }

    private void reserveComponents(ProductApprovalDTOs.SubmitRequest request) {
        ProductApprovalDTOs.Snapshot snapshot = request.getSnapshot();
        BigDecimal units = BigDecimal.valueOf(Math.max(0, snapshot.getStockQuantity()));
        Map<Long, BigDecimal> target = new HashMap<>();
        for (ProductApprovalDTOs.Component component : Optional.ofNullable(snapshot.getComponents()).orElse(List.of())) {
            target.merge(component.getItemId(), component.getQuantity().multiply(units), BigDecimal::add);
        }
        try {
            itemStockMovementService.reconcileReference(STOCK_SOURCE, stockReference(request.getEcommerceProductId()),
                    target, currentUserProvider.getCurrentUser().getUsername());
        } catch (ApiException exception) {
            if (!"INSUFFICIENT_ITEM_STOCK".equals(exception.getErrorCode())) {
                throw exception;
            }
            String message = "Not enough component stock to resubmit this product. " + exception.getMessage()
                    + ". Add stock for these items, then submit again.";
            throw new ApiException(message, "INSUFFICIENT_ITEM_STOCK", HttpStatus.CONFLICT,
                    List.of(new ApiResponse.ApiErrorItem("snapshot.components", exception.getMessage())));
        }
    }

    // e.g. " Component stock returned: 2 Motherboard, 1 Cable."
    private String describeReturned(ItemStockMovementDTOs.MovementResponse movement) {
        if (movement == null || movement.getLines().isEmpty()) {
            return "";
        }
        Map<Long, String> names = itemRepository.findAllById(movement.getLines().stream()
                        .map(ItemStockMovementDTOs.LineResult::getItemId).toList()).stream()
                .collect(Collectors.toMap(Item::getId, Item::getName));
        return " Component stock returned: " + movement.getLines().stream()
                .map(line -> line.getQuantity().negate().stripTrailingZeros().toPlainString() + " "
                        + names.getOrDefault(line.getItemId(), "item #" + line.getItemId()))
                .collect(Collectors.joining(", ")) + ".";
    }

    private static String stockReference(Long ecommerceProductId) {
        return "product:" + ecommerceProductId;
    }

    private List<Long> parseProductIds(String productIds) {
        String message = "productIds must be 1 to " + MAX_PRODUCT_IDS + " comma-separated product IDs";
        if (productIds == null || productIds.isBlank()) {
            throw fieldError("productIds", message);
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : productIds.split(",")) {
            String value = part.trim();
            if (!value.matches("^[1-9][0-9]{0,18}$")) {
                throw fieldError("productIds", message + "; '" + value + "' is not a valid ID");
            }
            ids.add(Long.parseLong(value));
        }
        if (ids.size() > MAX_PRODUCT_IDS) {
            throw fieldError("productIds", message);
        }
        return List.copyOf(ids);
    }

    private List<ProductApprovalDTOs.Response> toResponses(List<ProductApprovalRequest> requests) {
        Set<Long> userIds = requests.stream()
                .flatMap(r -> Stream.of(r.getRequestedBy(), r.getDecidedBy()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> usernames = userIds.isEmpty() ? Map.of() : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));

        return requests.stream()
                .map(r -> ProductApprovalDTOs.Response.builder()
                        .id(r.getId())
                        .ecommerceProductId(r.getEcommerceProductId())
                        .productTitle(r.getProductTitle())
                        .requestType(r.getRequestType())
                        .status(r.getStatus())
                        .snapshot(r.getSnapshot())
                        .requestedBy(r.getRequestedBy())
                        .requestedByUsername(usernames.get(r.getRequestedBy()))
                        .requestedAt(r.getRequestedAt())
                        .decidedBy(r.getDecidedBy())
                        .decidedByUsername(r.getDecidedBy() == null ? null : usernames.get(r.getDecidedBy()))
                        .decidedAt(r.getDecidedAt())
                        .decisionReason(r.getDecisionReason())
                        .proposedChanges(r.getProposedChanges())
                        .previousSnapshot(r.getPreviousSnapshot())
                        .changeSummary(r.getChangeSummary())
                        .appliedAt(r.getAppliedAt())
                        .appliedBy(r.getAppliedBy())
                        .build())
                .toList();
    }

    private ApiException pendingConflict() {
        return error(HttpStatus.CONFLICT, "PRODUCT_APPROVAL_PENDING", "This product is already waiting for Admin L2 approval.");
    }

    private ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(message, code, status);
    }

    private ApiException fieldError(String field, String message) {
        return new ApiException(message, "VALIDATION_FAILED", HttpStatus.BAD_REQUEST,
                List.of(new ApiResponse.ApiErrorItem(field, message)));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
