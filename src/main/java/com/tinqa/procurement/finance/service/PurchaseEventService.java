package com.tinqa.procurement.finance.service;

import com.tinqa.procurement.finance.dto.PurchaseEventDTOs;
import com.tinqa.procurement.finance.entity.PurchasePosting;
import com.tinqa.procurement.finance.repository.PurchasePostingRepository;
import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.invoice.OrderInvoice;
import com.tinqa.procurement.order.invoice.OrderInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Reads the purchase postings (append-only, written in the same transaction as each stock change) for the company
 * account. The postings are the outbox: nothing extra has to be written when stock moves.
 */
@Service
@RequiredArgsConstructor
public class PurchaseEventService {

    public static final int MAX_LIMIT = 500;

    private final PurchasePostingRepository postingRepository;
    private final OrderInvoiceRepository invoiceRepository;

    /** Events written at or after {@code since}, oldest first. The reader re-reads an overlap and de-duplicates by postingKey. */
    @Transactional(readOnly = true)
    public PurchaseEventDTOs.PurchaseEventPage eventsSince(Instant since, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        Instant from = since == null ? Instant.EPOCH : since;
        List<PurchasePosting> rows = postingRepository.findSince(from, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<PurchaseEventDTOs.PurchaseEvent> events = (hasMore ? rows.subList(0, size) : rows).stream()
                .map(this::toEvent)
                .toList();
        return new PurchaseEventDTOs.PurchaseEventPage(events, hasMore);
    }

    private PurchaseEventDTOs.PurchaseEvent toEvent(PurchasePosting posting) {
        Order order = posting.getOrder();
        Dealer dealer = order.getDealer();
        // The dealer's invoice that counts for the order (the latest uploaded): the document the input tax is claimed on
        OrderInvoice invoice = invoiceRepository.findFirstByOrderIdOrderByIdDesc(order.getId()).orElse(null);
        return new PurchaseEventDTOs.PurchaseEvent(
                posting.getId(),
                posting.postingKey(),
                posting.getReason().name(),
                order.getOrderNumber(),
                posting.getStock() != null ? posting.getStock().getStockIdentityNumber() : null,
                posting.getAmount(),
                dealer != null ? dealer.getName() : null,
                order.getItem() != null ? order.getItem().getName() : null,
                posting.getUnitsPassed(),
                order.getUnitType(),
                posting.getOccurredOn(),
                posting.getCreatedAt(),
                posting.getCgst(),
                posting.getSgst(),
                posting.getIgst(),
                posting.getCess(),
                posting.getGstRate(),
                posting.getSupplyType(),
                posting.getHsnSac(),
                dealer != null ? dealer.getGstin() : null,
                dealer != null ? dealer.getState() : null,
                invoice != null ? invoice.getInvoiceNumber() : null,
                invoice != null ? invoice.getUploadedAt() : null);
    }
}
