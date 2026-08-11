package ru.safeai.gateway.audit.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Objects;

/**
 * Stable JSON contract for paged audit responses.
 *
 * <p>Returning Spring Data Page/PageImpl directly makes the public JSON
 * representation dependent on Spring Data internals. This DTO keeps the
 * HTTP contract explicit and stable.</p>
 */
public record AuditEventPageResponse(
        List<AuditEventResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages
) {
    public AuditEventPageResponse {
        content = content == null
                ? List.of()
                : List.copyOf(content);

        if (number < 0) {
            throw new IllegalArgumentException(
                    "number не может быть отрицательным"
            );
        }

        if (size < 0) {
            throw new IllegalArgumentException(
                    "size не может быть отрицательным"
            );
        }

        if (totalElements < 0) {
            throw new IllegalArgumentException(
                    "totalElements не может быть отрицательным"
            );
        }

        if (totalPages < 0) {
            throw new IllegalArgumentException(
                    "totalPages не может быть отрицательным"
            );
        }
    }

    public static AuditEventPageResponse from(
            Page<AuditEventResponse> source
    ) {
        Objects.requireNonNull(
                source,
                "source не должен быть null"
        );

        return new AuditEventPageResponse(
                source.getContent(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages()
        );
    }
}
