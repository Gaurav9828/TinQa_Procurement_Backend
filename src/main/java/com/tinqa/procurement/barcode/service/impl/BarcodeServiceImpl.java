package com.tinqa.procurement.barcode.service.impl;

import com.tinqa.procurement.barcode.dto.BarcodeDTOs;
import com.tinqa.procurement.barcode.entity.Barcode;
import com.tinqa.procurement.barcode.entity.BarcodeEvent;
import com.tinqa.procurement.barcode.entity.BarcodeRequest;
import com.tinqa.procurement.barcode.enums.BarcodeRequestStatus;
import com.tinqa.procurement.barcode.enums.BarcodeRequestType;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import com.tinqa.procurement.barcode.repository.BarcodeEventRepository;
import com.tinqa.procurement.barcode.repository.BarcodeRepository;
import com.tinqa.procurement.barcode.repository.BarcodeRequestRepository;
import com.tinqa.procurement.barcode.service.BarcodeCodes;
import com.tinqa.procurement.barcode.service.BarcodeRegistry;
import com.tinqa.procurement.barcode.service.BarcodeService;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class BarcodeServiceImpl implements BarcodeService {

    // Longest list one lookup returns (a stock entry's units, an item's units in stock)
    static final int MAX_LIST = 5000;

    private final BarcodeRepository barcodes;
    private final BarcodeEventRepository events;
    private final BarcodeRequestRepository requests;
    private final BarcodeRegistry registry;
    private final StockRepository stockRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final NotificationService notificationService;
    private final Clock clock;

    // ------------------------------------------------------------------ lookups

    @Override
    @Transactional(readOnly = true)
    public BarcodeDTOs.Trace trace(String rawCode) {
        String code = BarcodeCodes.normalize(rawCode);
        if (!BarcodeCodes.isValid(code)) {
            throw ApiErrors.fieldError("code", "Barcode " + ValidationPatterns.BARCODE_MESSAGE);
        }
        Barcode barcode = barcodes.findLiveByCode(code)
                .or(() -> barcodes.findByCodeOrderByIdDesc(code, PageRequest.of(0, 1)).stream().findFirst())
                .orElseThrow(() -> ApiErrors.of(HttpStatus.NOT_FOUND, ErrorCodes.BARCODE_NOT_FOUND, "No unit carries barcode " + code));

        List<Barcode> parts = barcode.getUnitType() == BarcodeUnitType.PRODUCT_UNIT
                ? barcodes.findByFittedIntoIdInOrderByIdAsc(List.of(barcode.getId()))
                : List.of();
        List<BarcodeEvent> history = events.findByBarcodeIdOrderByIdAsc(barcode.getId());
        List<BarcodeRequest> asked = requests.findByBarcodeIdOrderByIdDesc(barcode.getId());

        Set<Long> userIds = new HashSet<>();
        Stream.concat(Stream.of(barcode), parts.stream())
                .map(Barcode::getStock).filter(Objects::nonNull)
                .forEach(stock -> {
                    userIds.add(stock.getCreatedBy());
                    userIds.add(stock.getApprovedBy());
                });
        asked.forEach(request -> {
            userIds.add(request.getRequestedBy());
            userIds.add(request.getDecidedBy());
        });
        Map<Long, String> usernames = usernames(userIds);
        Map<Long, String> codesById = codesOf(history.stream()
                .flatMap(event -> Stream.of(event.getFromFittedIntoId(), event.getToFittedIntoId())).filter(Objects::nonNull).toList());

        return BarcodeDTOs.Trace.builder()
                .unit(toUnit(barcode))
                .source(barcode.getStock() == null ? null : toSource(barcode.getStock(), usernames))
                .fittedInto(barcode.getFittedInto() == null ? null : toUnit(barcode.getFittedInto()))
                .parts(parts.stream().map(part -> BarcodeDTOs.Part.builder()
                        .unit(toUnit(part))
                        .source(toSource(part.getStock(), usernames))
                        .build()).toList())
                .history(history.stream().map(event -> BarcodeDTOs.Event.builder()
                        .eventType(event.getEventType())
                        .fromStatus(event.getFromStatus())
                        .toStatus(event.getToStatus())
                        .oldCode(event.getOldCode())
                        .newCode(event.getNewCode())
                        .fittedIntoCode(fittedIntoCode(event, codesById))
                        .note(event.getNote())
                        .actorName(event.getActorName())
                        .at(event.getCreatedAt())
                        .build()).toList())
                .requests(asked.stream().map(request -> toResponse(request, usernames, null)).toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BarcodeDTOs.Unit> list(Long stockId, Long itemId, BarcodeStatus status) {
        if ((stockId == null) == (itemId == null)) {
            throw ApiErrors.fieldError("stockId", "Give either stockId or itemId");
        }
        List<Barcode> found = stockId != null
                ? barcodes.findByStockIdOrderByIdAsc(stockId).stream().filter(b -> status == null || b.getStatus() == status).toList()
                : barcodes.findByItemIdAndStatusOrderByIdAsc(itemId, status == null ? BarcodeStatus.IN_STOCK : status, PageRequest.of(0, MAX_LIST));
        return found.stream().limit(MAX_LIST).map(BarcodeServiceImpl::toUnit).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BarcodeDTOs.ProductUnit> productUnits(Long ecommerceProductId) {
        List<Barcode> units = barcodes.findByUnitTypeAndEcommerceProductIdOrderByIdAsc(BarcodeUnitType.PRODUCT_UNIT, ecommerceProductId)
                .stream().filter(unit -> unit.getStatus() != BarcodeStatus.VOID).toList();
        if (units.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Barcode>> partsByUnit = barcodes.findByFittedIntoIdInOrderByIdAsc(units.stream().map(Barcode::getId).toList())
                .stream().collect(Collectors.groupingBy(part -> part.getFittedInto().getId()));
        return units.stream().map(unit -> BarcodeDTOs.ProductUnit.builder()
                .unit(toUnit(unit))
                .parts(partsByUnit.getOrDefault(unit.getId(), List.of()).stream().map(BarcodeServiceImpl::toUnit).toList())
                .build()).toList();
    }

    // ------------------------------------------------------------------ requests

    @Override
    @Transactional
    public BarcodeDTOs.RequestResponse createRequest(BarcodeDTOs.CreateRequest request) {
        User requester = currentUserProvider.getCurrentUser();
        String reason = request.getReason().trim();
        BarcodeRequest saved = request.getRequestType() == BarcodeRequestType.CORRECTION
                ? requestCorrection(request, requester, reason)
                : requestAssignment(request, requester, reason);

        // A Super Admin's own request needs nobody else: it is applied now and kept as history
        if (currentAccess.has(Permission.BARCODE_APPROVE)) {
            apply(saved, requester);
            saved.setStatus(BarcodeRequestStatus.APPROVED);
            saved.setDecidedBy(requester.getId());
            saved.setDecidedAt(LocalDateTime.now(clock));
            saved = requests.saveAndFlush(saved);
        } else {
            notificationService.createForPermission(Permission.BARCODE_APPROVE, requester.getId(), "Barcode request awaiting approval",
                    describe(saved) + " Requested by " + requester.getUsername() + ". Reason: " + reason, NotificationLinks.APPROVALS);
        }
        return toResponses(List.of(saved)).getFirst();
    }

    private BarcodeRequest requestCorrection(BarcodeDTOs.CreateRequest request, User requester, String reason) {
        if (request.getBarcodeId() == null) {
            throw ApiErrors.fieldError("barcodeId", "Barcode ID is required for a correction");
        }
        Barcode barcode = barcodes.findAllByIdForUpdate(List.of(request.getBarcodeId())).stream().findFirst()
                .filter(found -> found.getStatus() != BarcodeStatus.VOID)
                .orElseThrow(() -> ApiErrors.of(HttpStatus.NOT_FOUND, ErrorCodes.BARCODE_NOT_FOUND,
                        "Barcode not found with ID: " + request.getBarcodeId()));
        String newCode = requireNewCode(request.getNewCode(), barcode);
        boolean open = requests.findByBarcodeIdOrderByIdDesc(barcode.getId()).stream()
                .anyMatch(existing -> existing.getStatus() == BarcodeRequestStatus.PENDING);
        if (open) {
            throw ApiErrors.conflict(ErrorCodes.BARCODE_CORRECTION_PENDING,
                    "A correction of barcode " + barcode.getCode() + " is already waiting for a Super Admin.");
        }
        return requests.saveAndFlush(BarcodeRequest.builder()
                .requestType(BarcodeRequestType.CORRECTION)
                .status(BarcodeRequestStatus.PENDING)
                .barcode(barcode)
                .currentCode(barcode.getCode())
                .newCode(newCode)
                .reason(reason)
                .requestedBy(requester.getId())
                .requestedAt(LocalDateTime.now(clock))
                .build());
    }

    private BarcodeRequest requestAssignment(BarcodeDTOs.CreateRequest request, User requester, String reason) {
        if (request.getStockId() == null) {
            throw ApiErrors.fieldError("stockId", "Stock ID is required to assign barcodes");
        }
        Stock stock = lockAssignableStock(request.getStockId());
        List<String> codes = requireAssignableCodes(stock, request.getCodes());
        return requests.saveAndFlush(BarcodeRequest.builder()
                .requestType(BarcodeRequestType.ASSIGNMENT)
                .status(BarcodeRequestStatus.PENDING)
                .stock(stock)
                .codes(codes)
                .reason(reason)
                .requestedBy(requester.getId())
                .requestedAt(LocalDateTime.now(clock))
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<BarcodeDTOs.RequestResponse> getRequests(BarcodeRequestStatus status) {
        return toResponses(requests.findByStatusOrderByRequestedAtAscIdAsc(status == null ? BarcodeRequestStatus.PENDING : status));
    }

    @Override
    @Transactional
    public BarcodeDTOs.RequestResponse decide(Long id, BarcodeDTOs.DecisionRequest request) {
        User reviewer = currentUserProvider.getCurrentUser();
        BarcodeRequestStatus decision = request.getDecision();
        String reason = request.getReason() == null ? null : request.getReason().trim();
        if (decision == BarcodeRequestStatus.PENDING) {
            throw ApiErrors.fieldError("decision", "Decision must be APPROVED or REJECTED");
        }
        if (decision == BarcodeRequestStatus.REJECTED && (reason == null || reason.isEmpty())) {
            throw ApiErrors.fieldError("reason", "A reason is required to reject a barcode request");
        }
        // Row lock: of two Super Admins deciding at once, the second sees the first one's decision
        BarcodeRequest found = requests.findByIdForUpdate(id)
                .orElseThrow(() -> ApiErrors.of(HttpStatus.NOT_FOUND, ErrorCodes.BARCODE_REQUEST_NOT_FOUND,
                        "Barcode request not found with ID: " + id));
        if (found.getStatus() != BarcodeRequestStatus.PENDING) {
            throw ApiErrors.conflict(ErrorCodes.BARCODE_REQUEST_NOT_PENDING,
                    "This request was already " + found.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (decision == BarcodeRequestStatus.APPROVED) {
            apply(found, reviewer);
        }
        found.setStatus(decision);
        found.setDecidedBy(reviewer.getId());
        found.setDecidedAt(LocalDateTime.now(clock));
        found.setDecisionReason(reason == null || reason.isEmpty() ? null : reason);
        BarcodeRequest saved = requests.saveAndFlush(found);

        if (!saved.getRequestedBy().equals(reviewer.getId())) {
            boolean approved = decision == BarcodeRequestStatus.APPROVED;
            notificationService.createForUser(saved.getRequestedBy(), approved ? "Barcode request approved" : "Barcode request rejected",
                    describe(saved) + (approved ? " Approved by " + reviewer.getUsername() + "."
                            : " Rejected by " + reviewer.getUsername() + ". Reason: " + reason), NotificationLinks.BARCODES);
        }
        return toResponses(List.of(saved)).getFirst();
    }

    // Checked again when applied: the codes may have been taken, or the units used, since the request was made
    private void apply(BarcodeRequest request, User actor) {
        BarcodeRegistry.Actor by = BarcodeRegistry.Actor.of(actor);
        if (request.getRequestType() == BarcodeRequestType.CORRECTION) {
            Barcode barcode = barcodes.findAllByIdForUpdate(List.of(request.getBarcode().getId())).getFirst();
            if (barcode.getStatus() == BarcodeStatus.VOID) {
                throw ApiErrors.conflict(ErrorCodes.BARCODE_NOT_AVAILABLE, "Barcode " + barcode.getCode() + " is void and can't be corrected.");
            }
            registry.correctCode(barcode, requireNewCode(request.getNewCode(), barcode), by, request.getReason());
            return;
        }
        Stock stock = lockAssignableStock(request.getStock().getId());
        registry.assignStockUnits(stock, requireAssignableCodes(stock, request.getCodes()), "codes", by, request.getReason());
    }

    private String requireNewCode(String raw, Barcode barcode) {
        String newCode = BarcodeCodes.normalize(raw);
        if (newCode == null) {
            throw ApiErrors.fieldError("newCode", "New barcode is required");
        }
        if (!BarcodeCodes.isValid(newCode)) {
            throw ApiErrors.fieldError("newCode", "New barcode " + ValidationPatterns.BARCODE_MESSAGE);
        }
        if (newCode.equals(barcode.getCode())) {
            throw ApiErrors.fieldError("newCode", "The new barcode is the same as the current one");
        }
        registry.requireFree(List.of(newCode), "newCode");
        return newCode;
    }

    // Only approved entries have units in stock to label
    private Stock lockAssignableStock(Long stockId) {
        Stock stock = stockRepository.findByIdForUpdate(stockId)
                .orElseThrow(() -> ApiErrors.of(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "Stock entry not found with id: " + stockId));
        if (stock.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw ApiErrors.fieldError("stockId", "Barcodes can only be assigned to approved stock entries");
        }
        return stock;
    }

    // At most one code per available unit that has none
    private List<String> requireAssignableCodes(Stock stock, List<String> raw) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        List<String> codes = BarcodeCodes.normalizeAll(raw, "codes", errors);
        ApiErrors.throwIfAny(errors);
        int untracked = registry.untrackedUnits(stock);
        if (codes.isEmpty() || codes.size() > untracked) {
            String message = untracked == 0
                    ? "Every available unit of stock " + stock.getStockIdentityNumber() + " already has a barcode"
                    : "Enter 1 to " + untracked + " barcodes: stock " + stock.getStockIdentityNumber() + " has "
                    + untracked + " available units without one (" + codes.size() + " entered)";
            throw new ApiException(message, ErrorCodes.BARCODES_REQUIRED, HttpStatus.BAD_REQUEST, List.of(ApiErrors.error("codes", message)));
        }
        registry.requireFree(codes, "codes");
        return codes;
    }

    // ------------------------------------------------------------------ mapping

    private List<BarcodeDTOs.RequestResponse> toResponses(List<BarcodeRequest> found) {
        Map<Long, String> usernames = usernames(found.stream()
                .flatMap(request -> Stream.of(request.getRequestedBy(), request.getDecidedBy())).collect(Collectors.toSet()));
        List<Stock> stocks = found.stream().map(BarcodeRequest::getStock).filter(Objects::nonNull).distinct().toList();
        Map<Long, BigDecimal> untracked = registry.untrackedUnits(stocks);
        return found.stream().map(request -> toResponse(request, usernames, untracked)).toList();
    }

    private static BarcodeDTOs.RequestResponse toResponse(BarcodeRequest request, Map<Long, String> usernames,
                                                          Map<Long, BigDecimal> untracked) {
        Barcode barcode = request.getBarcode();
        Stock stock = request.getStock() != null ? request.getStock() : barcode == null ? null : barcode.getStock();
        return BarcodeDTOs.RequestResponse.builder()
                .id(request.getId())
                .requestType(request.getRequestType())
                .status(request.getStatus())
                .barcodeId(barcode == null ? null : barcode.getId())
                .currentCode(request.getCurrentCode())
                .newCode(request.getNewCode())
                .unitType(barcode == null ? BarcodeUnitType.ITEM_UNIT : barcode.getUnitType())
                .itemName(stock == null ? null : stock.getItem().getName())
                .productTitle(barcode == null ? null : barcode.getProductTitle())
                .stockId(stock == null ? null : stock.getId())
                .stockIdentityNumber(stock == null ? null : stock.getStockIdentityNumber())
                .batchNumber(stock == null ? null : stock.getBatchNumber())
                .unitsWithoutBarcode(untracked == null || request.getStock() == null ? null : untracked.get(request.getStock().getId()))
                .codes(request.getCodes())
                .reason(request.getReason())
                .requestedBy(request.getRequestedBy())
                .requestedByUsername(usernames.get(request.getRequestedBy()))
                .requestedAt(request.getRequestedAt())
                .decidedBy(request.getDecidedBy())
                .decidedByUsername(request.getDecidedBy() == null ? null : usernames.get(request.getDecidedBy()))
                .decidedAt(request.getDecidedAt())
                .decisionReason(request.getDecisionReason())
                .build();
    }

    static BarcodeDTOs.Unit toUnit(Barcode barcode) {
        Stock stock = barcode.getStock();
        Barcode fittedInto = barcode.getFittedInto();
        return BarcodeDTOs.Unit.builder()
                .id(barcode.getId())
                .code(barcode.getCode())
                .unitType(barcode.getUnitType())
                .status(barcode.getStatus())
                .locked(barcode.isLocked())
                .statusNote(barcode.getStatusNote())
                .itemId(barcode.getItem() == null ? null : barcode.getItem().getId())
                .itemName(barcode.getItem() == null ? null : barcode.getItem().getName())
                .itemSku(barcode.getItem() == null ? null : barcode.getItem().getSku())
                .stockId(stock == null ? null : stock.getId())
                .stockIdentityNumber(stock == null ? null : stock.getStockIdentityNumber())
                .batchNumber(stock == null ? null : stock.getBatchNumber())
                .ecommerceProductId(fittedInto != null ? fittedInto.getEcommerceProductId() : barcode.getEcommerceProductId())
                .productTitle(fittedInto != null ? fittedInto.getProductTitle() : barcode.getProductTitle())
                .fittedIntoId(fittedInto == null ? null : fittedInto.getId())
                .fittedIntoCode(fittedInto == null ? null : fittedInto.getCode())
                .createdAt(barcode.getCreatedAt())
                .createdByName(barcode.getCreatedByName())
                .lockedAt(barcode.getLockedAt())
                .build();
    }

    private static BarcodeDTOs.Source toSource(Stock stock, Map<Long, String> usernames) {
        Order order = stock.getOrder();
        return BarcodeDTOs.Source.builder()
                .stockId(stock.getId())
                .stockIdentityNumber(stock.getStockIdentityNumber())
                .batchNumber(stock.getBatchNumber())
                .dateOfArrival(stock.getDateOfArrival())
                .stockApprovalStatus(stock.getApprovalStatus())
                .receivedBy(usernames.get(stock.getCreatedBy()))
                .approvedBy(stock.getApprovedBy() == null ? null : usernames.get(stock.getApprovedBy()))
                .orderNumber(order.getOrderNumber())
                .orderDate(order.getOrderDate())
                .deliveredOn(order.getActualDelivery())
                .dealerId(stock.getDealer().getId())
                .dealerName(stock.getDealer().getName())
                .build();
    }

    // The product unit the event fitted the piece into, or took it out of
    private static String fittedIntoCode(BarcodeEvent event, Map<Long, String> codesById) {
        Long unitId = event.getToFittedIntoId() != null ? event.getToFittedIntoId() : event.getFromFittedIntoId();
        return unitId == null ? null : codesById.get(unitId);
    }

    private Map<Long, String> usernames(Collection<Long> ids) {
        List<Long> known = ids.stream().filter(Objects::nonNull).distinct().toList();
        return known.isEmpty() ? Map.of() : userRepository.findAllById(known).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    private Map<Long, String> codesOf(Collection<Long> ids) {
        List<Long> known = ids.stream().distinct().toList();
        return known.isEmpty() ? Map.of() : barcodes.findAllById(known).stream()
                .collect(Collectors.toMap(Barcode::getId, Barcode::getCode));
    }

    // e.g. "Correct barcode ABC12345 → ABC12346 (Motherboard)."
    private static String describe(BarcodeRequest request) {
        if (request.getRequestType() == BarcodeRequestType.CORRECTION) {
            Barcode barcode = request.getBarcode();
            String what = barcode.getUnitType() == BarcodeUnitType.PRODUCT_UNIT
                    ? barcode.getProductTitle() : barcode.getItem().getName();
            return "Correct barcode " + request.getCurrentCode() + " → " + request.getNewCode() + " (" + what + ").";
        }
        Stock stock = request.getStock();
        return "Assign " + request.getCodes().size() + " barcode(s) to stock " + stock.getStockIdentityNumber()
                + " (" + stock.getItem().getName() + ").";
    }
}
