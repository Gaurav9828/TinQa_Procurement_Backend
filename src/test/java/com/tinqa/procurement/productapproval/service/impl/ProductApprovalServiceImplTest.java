package com.tinqa.procurement.productapproval.service.impl;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;
import com.tinqa.procurement.stock.service.ItemStockMovementService;
import com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs;
import com.tinqa.procurement.productapproval.entity.ProductApprovalRequest;
import com.tinqa.procurement.productapproval.enums.ProductApprovalRequestType;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import com.tinqa.procurement.productapproval.repository.ProductApprovalRequestRepository;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductApprovalServiceImplTest {

    @Mock private ProductApprovalRequestRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private NotificationService notificationService;
    @Mock private ItemStockMovementService itemStockMovementService;
    @Mock private ItemRepository itemRepository;

    @InjectMocks private ProductApprovalServiceImpl service;

    private final User maker = User.builder().id(4L).username("admin_l1").role(Role.ADMIN_L1).build();
    private final User checker = User.builder().id(2L).username("admin_l2").role(Role.ADMIN_L2).build();

    @BeforeEach
    void setUp() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProductApprovalRequest request = invocation.getArgument(0);
            if (request.getId() == null) {
                request.setId(20L);
            }
            return request;
        });
        when(userRepository.findAllById(any())).thenReturn(List.of(maker, checker));
    }

    // ---------- submit ----------

    private ProductApprovalDTOs.SubmitRequest submitRequest() {
        return ProductApprovalDTOs.SubmitRequest.builder()
                .ecommerceProductId(2L)
                .productTitle("Touch Panel")
                .snapshot(ProductApprovalDTOs.Snapshot.builder().title("Touch Panel").price(new BigDecimal("2999")).stockQuantity(1)
                        .components(List.of(ProductApprovalDTOs.Component.builder().itemId(7L).itemName("Motherboard")
                                .quantity(new BigDecimal("2")).build()))
                        .build())
                .build();
    }

    private void latest(ProductApprovalStatus status) {
        when(repository.findFirstByEcommerceProductIdOrderByRequestedAtDescIdDesc(2L)).thenReturn(Optional.ofNullable(
                status == null ? null : request(19L, status, maker.getId())));
    }

    @Test
    void firstSubmissionIsACreateRequestAndNotifiesAdminL2() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        latest(null);

        ProductApprovalDTOs.Response response = service.submit(submitRequest());

        assertEquals(ProductApprovalRequestType.CREATE, response.getRequestType());
        assertEquals(ProductApprovalStatus.PENDING, response.getStatus());
        assertEquals("admin_l1", response.getRequestedByUsername());
        assertEquals("Motherboard", response.getSnapshot().getComponents().getFirst().getItemName());
        verify(repository).lockProduct(2L);
        verify(notificationService).createForRole(Role.ADMIN_L2, 4L, "Product awaiting approval",
                "Touch Panel (#2) was submitted by admin_l1.");
    }

    @Test
    void submissionAfterRejectionIsAResubmission() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        latest(ProductApprovalStatus.REJECTED);

        assertEquals(ProductApprovalRequestType.RESUBMISSION, service.submit(submitRequest()).getRequestType());
        verify(notificationService).createForRole(eq(Role.ADMIN_L2), eq(4L), any(), eq("Touch Panel (#2) was resubmitted by admin_l1."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING:PRODUCT_APPROVAL_PENDING", "APPROVED:PRODUCT_ALREADY_APPROVED",
            "CANCELLED:PRODUCT_APPROVAL_CANCELLED"})
    void submissionIsRefusedUnlessTheLatestWasRejected(String statusAndCode) {
        String[] parts = statusAndCode.split(":");
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        latest(ProductApprovalStatus.valueOf(parts[0]));

        ApiException exception = assertThrows(ApiException.class, () -> service.submit(submitRequest()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(parts[1], exception.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void concurrentDuplicateIsReportedAsAlreadyPending() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        latest(null);
        doThrow(new DataIntegrityViolationException("uq_product_approval_requests_one_pending")).when(repository).saveAndFlush(any());

        ApiException exception = assertThrows(ApiException.class, () -> service.submit(submitRequest()));

        assertEquals("PRODUCT_APPROVAL_PENDING", exception.getErrorCode());
        assertEquals("This product is already waiting for Admin L2 approval.", exception.getMessage());
    }

    // ---------- reads ----------

    @Test
    void latestParsesAndDeduplicatesIds() {
        when(repository.findLatestForProducts(List.of(1L, 2L, 3L))).thenReturn(List.of());

        service.getLatestForProducts(" 1,2 ,3,2");

        verify(repository).findLatestForProducts(List.of(1L, 2L, 3L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1,,2", "1,abc", "0", "-5", "1;2", "99999999999999999999"})
    void invalidProductIdsAreRejected(String ids) {
        ApiException exception = assertThrows(ApiException.class, () -> service.getLatestForProducts(ids));
        assertEquals("VALIDATION_FAILED", exception.getErrorCode());
        assertEquals("productIds", exception.getErrors().getFirst().getField());
    }

    @Test
    void atMostTwoHundredProductIdsPerCall() {
        String twoHundred = LongStream.rangeClosed(1, 200).mapToObj(Long::toString).collect(Collectors.joining(","));
        when(repository.findLatestForProducts(any())).thenReturn(List.of());
        service.getLatestForProducts(twoHundred);

        assertThrows(ApiException.class, () -> service.getLatestForProducts(twoHundred + ",201"));
    }

    @Test
    void queueDefaultsToPending() {
        when(repository.findByStatusOrderByRequestedAtAscIdAsc(ProductApprovalStatus.PENDING)).thenReturn(List.of());

        service.getByStatus(null);

        verify(repository).findByStatusOrderByRequestedAtAscIdAsc(ProductApprovalStatus.PENDING);
    }

    // ---------- decide ----------

    private ProductApprovalRequest request(Long id, ProductApprovalStatus status, Long requestedBy) {
        return ProductApprovalRequest.builder().id(id).ecommerceProductId(2L).productTitle("Touch Panel")
                .requestType(ProductApprovalRequestType.CREATE).status(status).requestedBy(requestedBy)
                .requestedAt(OffsetDateTime.now()).version(0L).build();
    }

    private ProductApprovalDTOs.DecisionRequest decision(ProductApprovalStatus decision, String reason) {
        return ProductApprovalDTOs.DecisionRequest.builder().decision(decision).reason(reason).build();
    }

    @Test
    void approvalRecordsTheReviewerAndNotifiesTheRequester() {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(request(20L, ProductApprovalStatus.PENDING, 4L)));

        ProductApprovalDTOs.Response response = service.decide(20L, decision(ProductApprovalStatus.APPROVED, " "));

        assertEquals(ProductApprovalStatus.APPROVED, response.getStatus());
        assertEquals(2L, response.getDecidedBy());
        assertEquals("admin_l2", response.getDecidedByUsername());
        assertNotNull(response.getDecidedAt());
        assertNull(response.getDecisionReason());
        verify(notificationService).createForUser(4L, "Product approved", "Touch Panel (#2) was approved by admin_l2.");
        verifyNoInteractions(itemStockMovementService);
    }

    @Test
    void rejectionStoresTheTrimmedReasonAndTellsTheRequester() {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(request(20L, ProductApprovalStatus.PENDING, 4L)));

        ProductApprovalDTOs.Response response = service.decide(20L, decision(ProductApprovalStatus.REJECTED, "  Price too low  "));

        assertEquals("Price too low", response.getDecisionReason());
        verify(notificationService).createForUser(eq(4L), eq("Product rejected"),
                eq("Touch Panel (#2) was rejected by admin_l2. Reason: Price too low. Edit the product and submit it again."));
    }

    @ParameterizedTest
    @EnumSource(value = ProductApprovalStatus.class, names = {"REJECTED", "CANCELLED"})
    void rejectAndCancelNeedAReason(ProductApprovalStatus decision) {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);

        ApiException exception = assertThrows(ApiException.class, () -> service.decide(20L, decision(decision, "   ")));

        assertEquals("reason", exception.getErrors().getFirst().getField());
        verify(repository, never()).findByIdForUpdate(any());
    }

    @ParameterizedTest
    @EnumSource(value = ProductApprovalStatus.class, names = {"APPROVED", "REJECTED", "CANCELLED"})
    void onlyPendingRequestsCanBeDecided(ProductApprovalStatus current) {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(request(20L, current, 4L)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.decide(20L, decision(ProductApprovalStatus.APPROVED, null)));

        assertEquals("PRODUCT_APPROVAL_NOT_PENDING", exception.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void unknownRequestIsNotFound() {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.decide(99L, decision(ProductApprovalStatus.APPROVED, null)));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("PRODUCT_APPROVAL_NOT_FOUND", exception.getErrorCode());
    }

    @Test
    void pendingIsNotADecision() {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        assertThrows(ApiException.class, () -> service.decide(20L, decision(ProductApprovalStatus.PENDING, null)));
    }

    @Test
    void makerCannotCheckTheirOwnProduct() {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(request(20L, ProductApprovalStatus.PENDING, 2L)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.decide(20L, decision(ProductApprovalStatus.APPROVED, null)));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("PRODUCT_APPROVAL_SELF_DECISION", exception.getErrorCode());
    }

    @Test
    void selfDecisionCanBeAllowedByConfiguration() {
        ReflectionTestUtils.setField(service, "allowSelfDecision", true);
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(request(20L, ProductApprovalStatus.PENDING, 2L)));

        assertEquals(ProductApprovalStatus.APPROVED, service.decide(20L, decision(ProductApprovalStatus.APPROVED, null)).getStatus());
        verifyNoInteractions(notificationService);
    }

    // ---------- component stock ----------

    @ParameterizedTest
    @EnumSource(value = ProductApprovalStatus.class, names = {"REJECTED", "CANCELLED"})
    void rejectingOrCancellingReturnsTheProductsComponentStock(ProductApprovalStatus decision) {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(request(20L, ProductApprovalStatus.PENDING, 4L)));
        when(itemStockMovementService.releaseReference("ECOMMERCE_PRODUCT", "product:2", "admin_l2"))
                .thenReturn(ItemStockMovementDTOs.MovementResponse.builder().lines(List.of(
                        ItemStockMovementDTOs.LineResult.builder().itemId(7L).quantity(new BigDecimal("-2.000")).build())).build());
        when(itemRepository.findAllById(List.of(7L))).thenReturn(List.of(Item.builder().id(7L).name("Motherboard").build()));

        service.decide(20L, decision(decision, "Wrong price"));

        verify(itemStockMovementService).releaseReference("ECOMMERCE_PRODUCT", "product:2", "admin_l2");
        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).createForUser(eq(4L), any(), message.capture());
        assertTrue(message.getValue().endsWith(" Component stock returned: 2 Motherboard."), message.getValue());
    }

    @Test
    void resubmissionReservesComponentsTimesStockAgain() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        latest(ProductApprovalStatus.REJECTED);
        ProductApprovalDTOs.SubmitRequest request = submitRequest();
        request.getSnapshot().setStockQuantity(3);
        request.getSnapshot().setComponents(List.of(
                ProductApprovalDTOs.Component.builder().itemId(7L).itemName("Motherboard").quantity(new BigDecimal("2")).build(),
                ProductApprovalDTOs.Component.builder().itemId(8L).itemName("Cable").quantity(BigDecimal.ONE).build()));

        service.submit(request);

        verify(itemStockMovementService).reconcileReference("ECOMMERCE_PRODUCT", "product:2",
                Map.of(7L, new BigDecimal("6"), 8L, new BigDecimal("3")), "admin_l1");
    }

    @Test
    void firstSubmissionDoesNotTouchStockBecauseEcommerceReservedItOnCreate() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        latest(null);

        service.submit(submitRequest());

        verifyNoInteractions(itemStockMovementService);
    }

    @Test
    void resubmissionIsRefusedWhenComponentsAreOutOfStock() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        latest(ProductApprovalStatus.REJECTED);
        doThrow(new ApiException("Not enough stock for Motherboard: need 2, available 1", "INSUFFICIENT_ITEM_STOCK", HttpStatus.CONFLICT))
                .when(itemStockMovementService).reconcileReference(any(), any(), any(), any());

        ApiException exception = assertThrows(ApiException.class, () -> service.submit(submitRequest()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("INSUFFICIENT_ITEM_STOCK", exception.getErrorCode());
        assertEquals("Not enough component stock to resubmit this product. Not enough stock for Motherboard: need 2, available 1."
                + " Add stock for these items, then submit again.", exception.getMessage());
        assertEquals("snapshot.components", exception.getErrors().getFirst().getField());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(notificationService);
    }

    // ---------- UPDATE requests (changes to an approved product) ----------

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private ProductApprovalDTOs.SubmitRequest updateRequest() throws Exception {
        ProductApprovalDTOs.SubmitRequest request = submitRequest();
        request.setRequestType(ProductApprovalRequestType.UPDATE);
        request.setProposedChanges(JSON.readTree(
                "{\"title\":\"Adapter Pro\",\"price\":1006,\"description\":\"Line 1\\nSee https://tinqa.com/specs\",\"warranties\":[]}"));
        request.setPreviousSnapshot(ProductApprovalDTOs.Snapshot.builder().title("Approved Adapter").price(new BigDecimal("1006")).stockQuantity(1).build());
        request.setChangeSummary(List.of(ProductApprovalDTOs.ChangeSummaryEntry.builder()
                .label("Title").oldValue("Approved Adapter").newValue("Adapter Pro").build()));
        return request;
    }

    private ProductApprovalRequest latestRequest(ProductApprovalRequestType type, ProductApprovalStatus status, boolean applied) {
        ProductApprovalRequest latest = request(19L, status, maker.getId());
        latest.setRequestType(type);
        latest.setAppliedAt(applied ? OffsetDateTime.now() : null);
        return latest;
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "CREATE:APPROVED", "RESUBMISSION:APPROVED", "UPDATE:APPROVED:applied", "UPDATE:REJECTED"})
    void updateIsAllowedForApprovedOrLegacyProducts(String latest) throws Exception {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        if (!latest.equals("NONE")) {
            String[] parts = latest.split(":");
            when(repository.findFirstByEcommerceProductIdOrderByRequestedAtDescIdDesc(2L)).thenReturn(Optional.of(latestRequest(
                    ProductApprovalRequestType.valueOf(parts[0]), ProductApprovalStatus.valueOf(parts[1]), parts.length > 2)));
        }

        ProductApprovalDTOs.Response response = service.submit(updateRequest());

        assertEquals(ProductApprovalRequestType.UPDATE, response.getRequestType());
        assertEquals(ProductApprovalStatus.PENDING, response.getStatus());
        assertEquals("Adapter Pro", response.getProposedChanges().get("title").asText());
        assertEquals("Line 1\nSee https://tinqa.com/specs", response.getProposedChanges().get("description").asText());
        assertEquals("Approved Adapter", response.getPreviousSnapshot().getTitle());
        assertNull(response.getAppliedAt());
        verify(notificationService).createForRole(Role.ADMIN_L2, 4L, "Product update awaiting approval",
                "Touch Panel (#2): 1 change requested by admin_l1.");
        verifyNoInteractions(itemStockMovementService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE:PENDING:PRODUCT_APPROVAL_PENDING", "UPDATE:PENDING:PRODUCT_APPROVAL_PENDING",
            "UPDATE:APPROVED:PRODUCT_UPDATE_NOT_APPLIED", "CREATE:REJECTED:PRODUCT_NOT_APPROVED",
            "RESUBMISSION:REJECTED:PRODUCT_NOT_APPROVED", "CREATE:CANCELLED:PRODUCT_APPROVAL_CANCELLED"})
    void updateIsRefusedOtherwise(String latestAndCode) throws Exception {
        String[] parts = latestAndCode.split(":");
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        when(repository.findFirstByEcommerceProductIdOrderByRequestedAtDescIdDesc(2L)).thenReturn(Optional.of(latestRequest(
                ProductApprovalRequestType.valueOf(parts[0]), ProductApprovalStatus.valueOf(parts[1]), false)));

        ApiException exception = assertThrows(ApiException.class, () -> service.submit(updateRequest()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(parts[2], exception.getErrorCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateRequiresItsFieldsAndAnObjectWithinOneMegabyte() throws Exception {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        ProductApprovalDTOs.SubmitRequest missing = submitRequest();
        missing.setRequestType(ProductApprovalRequestType.UPDATE);

        ApiException exception = assertThrows(ApiException.class, () -> service.submit(missing));
        assertEquals(List.of("proposedChanges", "previousSnapshot", "changeSummary"),
                exception.getErrors().stream().map(e -> e.getField()).toList());

        ProductApprovalDTOs.SubmitRequest array = updateRequest();
        array.setProposedChanges(JSON.readTree("[1,2]"));
        assertEquals("Proposed changes must be a JSON object", assertThrows(ApiException.class, () -> service.submit(array)).getMessage());

        ProductApprovalDTOs.SubmitRequest huge = updateRequest();
        huge.setProposedChanges(JSON.createObjectNode().put("description", "x".repeat(1_000_001)));
        assertEquals("Proposed changes cannot exceed 1 MB", assertThrows(ApiException.class, () -> service.submit(huge)).getMessage());
    }

    @Test
    void onlyUpdateMayBeSentAsRequestType() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        ProductApprovalDTOs.SubmitRequest request = submitRequest();
        request.setRequestType(ProductApprovalRequestType.RESUBMISSION);

        assertEquals("requestType", assertThrows(ApiException.class, () -> service.submit(request)).getErrors().getFirst().getField());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE:REJECTED", "UPDATE:APPROVED", "CREATE:APPROVED"})
    void approvedProductsCannotBeResubmittedWithoutUpdate(String latest) {
        String[] parts = latest.split(":");
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        when(repository.findFirstByEcommerceProductIdOrderByRequestedAtDescIdDesc(2L)).thenReturn(Optional.of(latestRequest(
                ProductApprovalRequestType.valueOf(parts[0]), ProductApprovalStatus.valueOf(parts[1]), true)));

        assertEquals("PRODUCT_ALREADY_APPROVED", assertThrows(ApiException.class, () -> service.submit(submitRequest())).getErrorCode());
    }

    @Test
    void productWithAnEarlierApprovalCannotBeResubmitted() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        latest(ProductApprovalStatus.REJECTED);
        when(repository.existsByEcommerceProductIdAndStatus(2L, ProductApprovalStatus.APPROVED)).thenReturn(true);

        assertEquals("PRODUCT_ALREADY_APPROVED", assertThrows(ApiException.class, () -> service.submit(submitRequest())).getErrorCode());
    }

    private ProductApprovalRequest pendingUpdate(Long requestedBy) {
        ProductApprovalRequest update = request(20L, ProductApprovalStatus.PENDING, requestedBy);
        update.setRequestType(ProductApprovalRequestType.UPDATE);
        return update;
    }

    @Test
    void changesCannotBeCancelled() {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(pendingUpdate(4L)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.decide(20L, decision(ProductApprovalStatus.CANCELLED, "no")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("PRODUCT_APPROVAL_INVALID_DECISION", exception.getErrorCode());
        assertEquals("Product changes can only be approved or rejected.", exception.getMessage());
    }

    @Test
    void rejectedChangesLeaveTheLiveProductsStockAloneAndTellTheRequester() {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(pendingUpdate(4L)));

        service.decide(20L, decision(ProductApprovalStatus.REJECTED, "Price too low"));

        verifyNoInteractions(itemStockMovementService);
        verify(notificationService).createForUser(4L, "Product changes rejected",
                "Your changes to Touch Panel (#2) were rejected by admin_l2. Reason: Price too low."
                        + " The product is unchanged; you can submit new changes.");
    }

    @Test
    void approvedChangesNotifyTheRequester() {
        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(pendingUpdate(4L)));

        assertEquals(ProductApprovalStatus.APPROVED, service.decide(20L, decision(ProductApprovalStatus.APPROVED, null)).getStatus());
        verify(notificationService).createForUser(4L, "Product changes approved", "Your changes to Touch Panel (#2) were approved by admin_l2.");
    }

    // ---------- /applied ----------

    @Test
    void markingAppliedRecordsWhoAndWhenOnce() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        ProductApprovalRequest approved = pendingUpdate(4L);
        approved.setStatus(ProductApprovalStatus.APPROVED);
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(approved));

        ProductApprovalDTOs.Response first = service.markApplied(20L);
        assertNotNull(first.getAppliedAt());
        assertEquals(4L, first.getAppliedBy());

        when(currentUserProvider.getCurrentUser()).thenReturn(checker);
        ProductApprovalDTOs.Response retry = service.markApplied(20L);
        assertEquals(first.getAppliedAt(), retry.getAppliedAt());
        assertEquals(4L, retry.getAppliedBy());
        verify(repository, times(1)).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE:PENDING", "UPDATE:REJECTED", "CREATE:APPROVED", "RESUBMISSION:APPROVED"})
    void onlyApprovedChangesCanBeMarkedApplied(String state) {
        String[] parts = state.split(":");
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        ProductApprovalRequest request = request(20L, ProductApprovalStatus.valueOf(parts[1]), 4L);
        request.setRequestType(ProductApprovalRequestType.valueOf(parts[0]));
        when(repository.findByIdForUpdate(20L)).thenReturn(Optional.of(request));

        ApiException exception = assertThrows(ApiException.class, () -> service.markApplied(20L));

        assertEquals("PRODUCT_APPROVAL_NOT_APPLICABLE", exception.getErrorCode());
        assertEquals("Only approved product changes can be marked as applied.", exception.getMessage());
    }

    @Test
    void markingAnUnknownRequestIsNotFound() {
        when(currentUserProvider.getCurrentUser()).thenReturn(maker);
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertEquals("PRODUCT_APPROVAL_NOT_FOUND", assertThrows(ApiException.class, () -> service.markApplied(99L)).getErrorCode());
    }
}
