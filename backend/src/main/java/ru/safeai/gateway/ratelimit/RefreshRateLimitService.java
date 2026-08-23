package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.Timer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;
import java.util.Objects;

@Service
@EnableConfigurationProperties(
        RefreshRateLimitProperties.class
)
public final class RefreshRateLimitService {

    private static final String RATE_LIMIT_TYPE =
            "refresh";

    private static final String REJECTED_DIMENSION =
            "ip";

    private static final String CHECK_OPERATION =
            "check";

    private static final String SUCCESS_OUTCOME =
            "success";

    private static final String ERROR_OUTCOME =
            "error";

    private static final String PUBLIC_LIMIT_MESSAGE =
            "Слишком много запросов обновления сессии. Попробуйте позже";

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final RefreshRateLimitProperties properties;
    private final RateLimitKeyFactory keyFactory;
    private final RateLimitMetrics metrics;

    public RefreshRateLimitService(
            RedisFixedWindowRateLimiter rateLimiter,
            RefreshRateLimitProperties properties,
            RateLimitKeyFactory keyFactory,
            RateLimitMetrics metrics
    ) {
        this.rateLimiter = Objects.requireNonNull(
                rateLimiter,
                "rateLimiter не должен быть null"
        );

        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        this.keyFactory = Objects.requireNonNull(
                keyFactory,
                "keyFactory не должен быть null"
        );

        this.metrics = Objects.requireNonNull(
                metrics,
                "metrics не должен быть null"
        );
    }

    /**
     * Coarse anonymous IP limiter выполняется до refresh-token DB lookup.
     *
     * <p>Fail-closed: если Redis rate-limit infrastructure недоступна,
     * refresh возвращает 503 вместо неограниченного fallback на PostgreSQL.</p>
     */
    public void checkAllowed(
            String ipAddress
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        String normalizedIp =
                normalizeIp(ipAddress);

        String key =
                keyFactory.refreshIp(
                        normalizedIp
                );

        Timer.Sample sample =
                metrics.startRedisOperation();

        RateLimitResult result;

        try {
            result = rateLimiter.incrementAndGet(
                    key,
                    properties.effectiveWindow()
            );

            metrics.finishRedisOperation(
                    sample,
                    RATE_LIMIT_TYPE,
                    CHECK_OPERATION,
                    SUCCESS_OUTCOME
            );
        } catch (RuntimeException exception) {
            metrics.finishRedisOperation(
                    sample,
                    RATE_LIMIT_TYPE,
                    CHECK_OPERATION,
                    ERROR_OUTCOME
            );

            metrics.recordUnavailable(
                    RATE_LIMIT_TYPE
            );

            throw new RateLimitUnavailableException(
                    "Redis refresh rate limit недоступен",
                    exception
            );
        }

        if (result.count()
                <= properties.effectiveIpLimit()) {

            metrics.recordAllowed(
                    RATE_LIMIT_TYPE
            );

            return;
        }

        metrics.recordRejected(
                RATE_LIMIT_TYPE,
                REJECTED_DIMENSION
        );

        throw new RateLimitExceededException(
                PUBLIC_LIMIT_MESSAGE,
                Duration.ofSeconds(
                        result.ttlSeconds()
                )
        );
    }

    private String normalizeIp(
            String ipAddress
    ) {
        if (ipAddress == null
                || ipAddress.isBlank()) {
            return "unknown";
        }

        return ipAddress.trim();
    }
}
