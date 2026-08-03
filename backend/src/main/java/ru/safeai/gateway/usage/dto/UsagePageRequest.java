package ru.safeai.gateway.usage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public record UsagePageRequest(
        @Min(0)
        Integer page,

        @Min(1)
        @Max(200)
        Integer size
) {
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
                resolvedSize
        );
    }
}
