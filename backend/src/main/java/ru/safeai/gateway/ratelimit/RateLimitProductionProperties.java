package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Явные production-only escape hatches для rate-limit policy.
 *
 * <p>По умолчанию платный AI traffic обязан проходить через limiter.
 * Отключение AI limiter в production допускается только как сознательное
 * operational решение с явно включённым allowUnlimitedAiTraffic.</p>
 */
@ConfigurationProperties(
        prefix = "safeai.rate-limit.production"
)
public record RateLimitProductionProperties(
        Boolean allowUnlimitedAiTraffic
) {

    public RateLimitProductionProperties {
        allowUnlimitedAiTraffic =
                Boolean.TRUE.equals(
                        allowUnlimitedAiTraffic
                );
    }

    public boolean isUnlimitedAiTrafficAllowed() {
        return allowUnlimitedAiTraffic;
    }
}
