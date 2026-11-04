package com.tinqa.procurement.common.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Page requests with a capped size and the order the endpoint defines (a client's sort is ignored). */
public final class Pages {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 20;

    private Pages() {
    }

    public static Pageable capped(Pageable requested, Sort sort) {
        int page = requested == null ? 0 : Math.max(0, requested.getPageNumber());
        int size = requested == null ? DEFAULT_SIZE : Math.min(Math.max(1, requested.getPageSize()), MAX_SIZE);
        return PageRequest.of(page, size, sort);
    }

    public static Pageable capped(Pageable requested) {
        return capped(requested, Sort.unsorted());
    }
}
