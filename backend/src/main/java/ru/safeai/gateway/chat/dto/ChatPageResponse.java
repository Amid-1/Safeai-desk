package ru.safeai.gateway.chat.dto;

import org.springframework.data.domain.Slice;

import java.util.List;
import java.util.Objects;

public record ChatPageResponse<T>(
        List<T> content,
        int page,
        int size,
        boolean first,
        boolean last,
        boolean hasNext,
        boolean hasPrevious
) {
    public ChatPageResponse {
        content = List.copyOf(
                Objects.requireNonNull(content, "content не должен быть null")
        );
        if (page < 0) {
            throw new IllegalArgumentException("page не может быть отрицательным");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size должен быть положительным");
        }
    }

    public static <T> ChatPageResponse<T> from(Slice<T> slice) {
        Objects.requireNonNull(slice, "slice не должен быть null");
        return new ChatPageResponse<>(
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
