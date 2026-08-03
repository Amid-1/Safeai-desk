package ru.safeai.gateway.chat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record MessagePageRequest(
        @Min(0)
        Integer page,

        @Min(1)
        @Max(100)
        Integer size
) {
    public Pageable toPageable(int configuredMaxSize) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = Math.min(
                size == null ? 50 : size,
                configuredMaxSize
        );
        return PageRequest.of(
                resolvedPage,
                resolvedSize,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );
    }
}
