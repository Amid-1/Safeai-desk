package ru.safeai.gateway.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Objects;
import java.util.Set;

public final class StablePageableNormalizer {

    /**
     * Conservative compatibility bound for existing call sites that do not
     * provide an endpoint-specific offset limit yet.
     *
     * <p>Large/high-cardinality endpoints should prefer cursor/keyset
     * pagination and may pass a stricter value through the explicit overload.</p>
     */
    public static final long DEFAULT_MAX_OFFSET =
            1_000_000L;

    private StablePageableNormalizer() {
    }

    public enum TieBreakerDirectionPolicy {

        /**
         * Стабилизирующий sort всегда DESC.
         */
        DESCENDING,

        /**
         * Стабилизирующий sort получает направление
         * последнего пользовательского/default sort order.
         */
        FOLLOW_LAST_SORT_DIRECTION
    }

    /**
     * Backward-compatible entry point.
     *
     * <p>In addition to page-size validation it applies a finite offset
     * ceiling, so existing callers are not left vulnerable to arbitrarily
     * deep OFFSET pagination.</p>
     */
    public static Pageable normalize(
            Pageable pageable,
            int maxPageSize,
            Set<String> allowedSortProperties,
            Sort defaultSort,
            String tieBreakerProperty,
            TieBreakerDirectionPolicy tieBreakerDirectionPolicy
    ) {
        return normalize(
                pageable,
                maxPageSize,
                DEFAULT_MAX_OFFSET,
                allowedSortProperties,
                defaultSort,
                tieBreakerProperty,
                tieBreakerDirectionPolicy
        );
    }

    /**
     * Normalizes bounded offset pagination and stable sorting.
     *
     * @param maxOffset maximum allowed SQL-style offset; use a stricter value
     *                  for large tables and cursor pagination beyond it
     */
    public static Pageable normalize(
            Pageable pageable,
            int maxPageSize,
            long maxOffset,
            Set<String> allowedSortProperties,
            Sort defaultSort,
            String tieBreakerProperty,
            TieBreakerDirectionPolicy tieBreakerDirectionPolicy
    ) {
        Objects.requireNonNull(
                pageable,
                "pageable не должен быть null"
        );

        Objects.requireNonNull(
                allowedSortProperties,
                "allowedSortProperties не должен быть null"
        );

        Objects.requireNonNull(
                defaultSort,
                "defaultSort не должен быть null"
        );

        Objects.requireNonNull(
                tieBreakerProperty,
                "tieBreakerProperty не должен быть null"
        );

        Objects.requireNonNull(
                tieBreakerDirectionPolicy,
                "tieBreakerDirectionPolicy не должен быть null"
        );

        if (maxPageSize < 1) {
            throw new IllegalArgumentException(
                    "maxPageSize должен быть положительным"
            );
        }

        if (maxOffset < 0L) {
            throw new IllegalArgumentException(
                    "maxOffset не может быть отрицательным"
            );
        }

        if (tieBreakerProperty.isBlank()) {
            throw new IllegalArgumentException(
                    "tieBreakerProperty не должен быть пустым"
            );
        }

        if (defaultSort.isUnsorted()) {
            throw new IllegalArgumentException(
                    "defaultSort должен содержать сортировку"
            );
        }

        if (pageable.isUnpaged()) {
            throw new BadRequestException(
                    "Постраничный запрос обязателен"
            );
        }

        int pageNumber =
                pageable.getPageNumber();

        int pageSize =
                pageable.getPageSize();

        if (pageNumber < 0) {
            throw new BadRequestException(
                    "Номер страницы не может быть отрицательным"
            );
        }

        if (pageSize < 1
                || pageSize > maxPageSize) {

            throw new BadRequestException(
                    "Размер страницы должен быть от 1 до "
                            + maxPageSize
            );
        }

        validateOffset(
                pageNumber,
                pageSize,
                maxOffset
        );

        validateRequestedSort(
                pageable.getSort(),
                allowedSortProperties
        );

        Sort stableSort =
                pageable.getSort()
                        .isUnsorted()
                        ? defaultSort
                        : pageable.getSort();

        if (!containsProperty(
                stableSort,
                tieBreakerProperty
        )) {
            stableSort =
                    stableSort.and(
                            Sort.by(
                                    new Sort.Order(
                                            resolveTieBreakerDirection(
                                                    stableSort,
                                                    tieBreakerDirectionPolicy
                                            ),
                                            tieBreakerProperty
                                    )
                            )
                    );
        }

        return PageRequest.of(
                pageNumber,
                pageSize,
                stableSort
        );
    }

    private static void validateOffset(
            int pageNumber,
            int pageSize,
            long maxOffset
    ) {
        /*
         * Both operands are non-negative int values. Their maximum product
         * (Integer.MAX_VALUE ^ 2) is below Long.MAX_VALUE, so after promoting
         * the first operand to long this multiplication cannot overflow.
         */
        long offset =
                (long) pageNumber
                        * pageSize;

        if (offset > maxOffset) {
            throw new BadRequestException(
                    "Слишком глубокая страница. "
                            + "Используйте cursor pagination"
            );
        }
    }

    private static void validateRequestedSort(
            Sort sort,
            Set<String> allowedSortProperties
    ) {
        for (Sort.Order order : sort) {
            if (!allowedSortProperties.contains(
                    order.getProperty()
            )) {
                throw new BadRequestException(
                        "Сортировка по полю не разрешена: "
                                + order.getProperty()
                );
            }
        }
    }

    private static boolean containsProperty(
            Sort sort,
            String property
    ) {
        return sort.stream()
                .anyMatch(order ->
                        property.equals(
                                order.getProperty()
                        )
                );
    }

    private static Sort.Direction resolveTieBreakerDirection(
            Sort sort,
            TieBreakerDirectionPolicy policy
    ) {
        if (policy
                == TieBreakerDirectionPolicy.DESCENDING) {

            return Sort.Direction.DESC;
        }

        return sort.stream()
                .reduce(
                        (
                                first,
                                second
                        ) -> second
                )
                .map(
                        Sort.Order::getDirection
                )
                .orElse(
                        Sort.Direction.DESC
                );
    }
}
