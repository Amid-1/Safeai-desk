package ru.safeai.gateway.usage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record UsagePageRequest(
        @Min(0)
        Integer page,

        @Min(1)
        @Max(200)
        Integer size,
        Order order
) {
    public enum Order { tokens, total, cost, responses, coverage }

    public UsagePageRequest(Integer page, Integer size) {
        this(page, size, null);
    }
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 50;

    public Pageable toPageable() {
        int resolvedPage = page == null
                ? DEFAULT_PAGE
                : page;

        int resolvedSize = size == null
                ? DEFAULT_SIZE
                : size;

        return PageRequest.of(
                resolvedPage,
                resolvedSize,
                order == null ? Sort.unsorted() : Sort.by(Sort.Direction.DESC, order.name())
        );
    }
}
