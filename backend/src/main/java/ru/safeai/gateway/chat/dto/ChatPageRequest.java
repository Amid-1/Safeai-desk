package ru.safeai.gateway.chat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Set;

public record ChatPageRequest(
        @Min(0)
        Integer page,

        @Min(1)
        @Max(100)
        Integer size,

        @Pattern(regexp = "updatedAt|createdAt|title|id")
        String sortBy,

        @Pattern(regexp = "(?i)asc|desc")
        String direction
) {
    private static final Set<String> ALLOWED_SORT = Set.of(
            "updatedAt",
            "createdAt",
            "title",
            "id"
    );

    public Pageable toPageable(int configuredMaxSize) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = Math.min(
                size == null ? 20 : size,
                configuredMaxSize
        );
        String resolvedSort = sortBy == null || sortBy.isBlank()
                ? "updatedAt"
                : sortBy;
        if (!ALLOWED_SORT.contains(resolvedSort)) {
            throw new IllegalArgumentException(
                    "Недопустимое поле сортировки: " + resolvedSort
            );
        }

        Sort.Direction resolvedDirection = direction == null
                ? Sort.Direction.DESC
                : Sort.Direction.valueOf(
                        direction.toUpperCase(Locale.ROOT)
                );

        Sort sort = Sort.by(
                new Sort.Order(resolvedDirection, resolvedSort)
        );
        if (!"id".equals(resolvedSort)) {
            sort = sort.and(
                    Sort.by(new Sort.Order(resolvedDirection, "id"))
            );
        }
        return PageRequest.of(resolvedPage, resolvedSize, sort);
    }
}
