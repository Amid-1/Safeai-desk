package ru.safeai.gateway.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;

@ConfigurationProperties(prefix = "safeai.chat.quota")
public record ChatQuotaProperties(
        Boolean enabled,
        Long reservationInputTokens,
        Long reservationOutputTokens,
        BigDecimal reservationCostUsd,
        String periodZone
) {

    private static final long DEFAULT_RESERVATION_INPUT_TOKENS =
            16_000L;

    private static final long DEFAULT_RESERVATION_OUTPUT_TOKENS =
            2_048L;

    private static final BigDecimal DEFAULT_RESERVATION_COST_USD =
            new BigDecimal("1.000000000000");

    private static final String DEFAULT_PERIOD_ZONE = "UTC";

    private static final int MAX_COST_SCALE = 12;

    /*
     * PostgreSQL numeric(30, 12):
     * 30 значащих цифр, из которых максимум 12 после запятой.
     */
    private static final int MAX_COST_INTEGER_DIGITS = 18;

    public ChatQuotaProperties {
        enabled = enabled == null || enabled;

        reservationInputTokens = nonNegative(
                reservationInputTokens,
                DEFAULT_RESERVATION_INPUT_TOKENS,
                "reservation-input-tokens"
        );

        reservationOutputTokens = nonNegative(
                reservationOutputTokens,
                DEFAULT_RESERVATION_OUTPUT_TOKENS,
                "reservation-output-tokens"
        );

        reservationCostUsd = reservationCostUsd == null
                ? DEFAULT_RESERVATION_COST_USD
                : reservationCostUsd;

        validateReservationCost(reservationCostUsd);

        periodZone = normalizePeriodZone(periodZone);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public ZoneId effectivePeriodZone() {
        /*
         * periodZone уже проверен в compact constructor,
         * поэтому здесь исключение возможно только при программной ошибке.
         */
        return ZoneId.of(periodZone);
    }

    private static long nonNegative(
            Long value,
            long defaultValue,
            String propertyName
    ) {
        long resolved = value == null
                ? defaultValue
                : value;

        if (resolved < 0) {
            throw new IllegalStateException(
                    "safeai.chat.quota."
                            + propertyName
                            + " не может быть отрицательным"
            );
        }

        return resolved;
    }

    private static void validateReservationCost(
            BigDecimal value
    ) {
        if (value.signum() < 0) {
            throw new IllegalStateException(
                    "safeai.chat.quota.reservation-cost-usd "
                            + "не может быть отрицательным"
            );
        }

        if (value.scale() > MAX_COST_SCALE) {
            throw new IllegalStateException(
                    "safeai.chat.quota.reservation-cost-usd "
                            + "должен иметь scale не более "
                            + MAX_COST_SCALE
            );
        }

        int integerDigits = Math.max(
                0,
                value.precision() - value.scale()
        );

        if (integerDigits > MAX_COST_INTEGER_DIGITS) {
            throw new IllegalStateException(
                    "safeai.chat.quota.reservation-cost-usd "
                            + "должен содержать не более "
                            + MAX_COST_INTEGER_DIGITS
                            + " цифр до десятичной точки"
            );
        }
    }

    private static String normalizePeriodZone(
            String value
    ) {
        String normalized = value == null || value.isBlank()
                ? DEFAULT_PERIOD_ZONE
                : value.trim();

        try {
            /*
             * getId() возвращает нормализованное значение,
             * а результат ZoneId.of() больше не игнорируется.
             */
            return ZoneId.of(normalized).getId();
        } catch (DateTimeException exception) {
            throw new IllegalStateException(
                    "safeai.chat.quota.period-zone содержит "
                            + "неизвестную временную зону: "
                            + normalized,
                    exception
            );
        }
    }
}