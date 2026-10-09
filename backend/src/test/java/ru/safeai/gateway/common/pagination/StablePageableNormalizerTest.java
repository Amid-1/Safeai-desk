package ru.safeai.gateway.common.pagination;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StablePageableNormalizerTest {

    private static final Set<String> ALLOWED_SORT =
            Set.of(
                    "createdAt",
                    "id"
            );

    private static final Sort DEFAULT_SORT =
            Sort.by(
                    Sort.Order.desc(
                            "createdAt"
                    )
            );

    @Test
    void rejectsDeepOffset() {
        Pageable pageable =
                PageRequest.of(
                        10_001,
                        100
                );

        assertThatThrownBy(() ->
                StablePageableNormalizer.normalize(
                        pageable,
                        100,
                        1_000_000L,
                        ALLOWED_SORT,
                        DEFAULT_SORT,
                        "id",
                        StablePageableNormalizer
                                .TieBreakerDirectionPolicy
                                .FOLLOW_LAST_SORT_DIRECTION
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "cursor pagination"
                );
    }

    @Test
    void acceptsOffsetAtConfiguredBoundary() {
        Pageable pageable =
                PageRequest.of(
                        10_000,
                        100
                );

        Pageable normalized =
                StablePageableNormalizer.normalize(
                        pageable,
                        100,
                        1_000_000L,
                        ALLOWED_SORT,
                        DEFAULT_SORT,
                        "id",
                        StablePageableNormalizer
                                .TieBreakerDirectionPolicy
                                .FOLLOW_LAST_SORT_DIRECTION
                );

        assertThat(
                normalized.getOffset()
        ).isEqualTo(
                1_000_000L
        );
    }

    @Test
    void existingOverloadStillAddsStableTieBreaker() {
        Pageable normalized =
                StablePageableNormalizer.normalize(
                        PageRequest.of(
                                0,
                                50,
                                Sort.by(
                                        Sort.Order.asc(
                                                "createdAt"
                                        )
                                )
                        ),
                        100,
                        ALLOWED_SORT,
                        DEFAULT_SORT,
                        "id",
                        StablePageableNormalizer
                                .TieBreakerDirectionPolicy
                                .FOLLOW_LAST_SORT_DIRECTION
                );

        assertThat(
                normalized.getSort()
                        .getOrderFor(
                                "id"
                        )
        )
                .isNotNull()
                .extracting(
                        Sort.Order::getDirection
                )
                .isEqualTo(
                        Sort.Direction.ASC
                );
    }
}
