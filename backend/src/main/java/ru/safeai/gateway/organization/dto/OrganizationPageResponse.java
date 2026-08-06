package ru.safeai.gateway.organization.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Objects;

public record OrganizationPageResponse(
        List<OrganizationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public OrganizationPageResponse {
        content = List.copyOf(
                Objects.requireNonNull(
                        content,
                        "content не должен быть null"
                )
        );

        if (page < 0) {
            throw new IllegalArgumentException(
                    "page не может быть отрицательной"
            );
        }

        if (size < 1) {
            throw new IllegalArgumentException(
                    "size должен быть положительным"
            );
        }

        if (totalElements < 0L) {
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

    public static OrganizationPageResponse from(
            Page<OrganizationResponse> source
    ) {
        Objects.requireNonNull(
                source,
                "source не должен быть null"
        );

        return new OrganizationPageResponse(
                source.getContent(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages()
        );
    }
}
