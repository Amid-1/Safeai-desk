package ru.safeai.gateway.usage.dto;

import org.springframework.data.domain.Slice;

import java.util.List;

public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        boolean first,
        boolean last,
        boolean hasNext,
        boolean hasPrevious
) {
    public PagedResponse {
        content = List.copyOf(content);
    }

    public static <T> PagedResponse<T> from(Slice<T> slice) {
        return new PagedResponse<>(
                slice.getContent(),
                slice.getNumber(),
                slice.getSize(),
                slice.isFirst(),
                slice.isLast(),
                slice.hasNext(),
                slice.hasPrevious()
        );
    }
}
