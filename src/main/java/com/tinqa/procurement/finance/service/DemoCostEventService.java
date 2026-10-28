package com.tinqa.procurement.finance.service;

import com.tinqa.procurement.clientdemo.ClientDemo;
import com.tinqa.procurement.clientdemo.ClientDemoDTOs;
import com.tinqa.procurement.clientdemo.ClientDemoRepository;
import com.tinqa.procurement.clientdemo.ClientCategory;
import com.tinqa.procurement.clientdemo.DemoPosting;
import com.tinqa.procurement.clientdemo.DemoPostingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads what approved client demo completions and corrections booked, for the company account, in creation order.
 * Postings are append-only, so the rows themselves are the outbox.
 */
@Service
@RequiredArgsConstructor
public class DemoCostEventService {

    public static final int MAX_LIMIT = 500;

    private final DemoPostingRepository postingRepository;
    private final ClientDemoRepository demoRepository;

    /** Events written at or after {@code since}, oldest first. The reader re-reads an overlap and de-duplicates by postingKey. */
    @Transactional(readOnly = true)
    public ClientDemoDTOs.PostingEventPage eventsSince(Instant since, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<DemoPosting> rows = postingRepository.findCreatedSince(since == null ? Instant.EPOCH : since, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<DemoPosting> page = hasMore ? rows.subList(0, size) : rows;
        Map<Long, ClientDemo> demos = demoRepository.findAllById(page.stream().map(DemoPosting::getDemoId).distinct().toList())
                .stream().collect(Collectors.toMap(ClientDemo::getId, Function.identity()));
        List<ClientDemoDTOs.PostingEvent> events = page.stream().map(posting -> {
            ClientDemo demo = demos.get(posting.getDemoId());
            return new ClientDemoDTOs.PostingEvent(
                    posting.getId(),
                    posting.postingKey(),
                    ClientDemo.reference(posting.getDemoId()),
                    demo != null ? demo.getClientName() : null,
                    demo == null ? null : demo.getClientCategory() == ClientCategory.OTHER && demo.getClientCategoryOther() != null
                            ? demo.getClientCategoryOther() : demo.getClientCategory().label(),
                    posting.getKind(),
                    posting.getAmount(),
                    posting.getOccurredOn(),
                    posting.getCreatedAt());
        }).toList();
        return new ClientDemoDTOs.PostingEventPage(events, hasMore);
    }
}
