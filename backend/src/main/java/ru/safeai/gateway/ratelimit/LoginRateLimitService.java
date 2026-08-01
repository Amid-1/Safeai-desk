package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(
        LoginRateLimitProperties.class
)
public class LoginRateLimitService {

    private static final String RATE_LIMIT_TYPE =
            "login";

    private static final String CHECK_OPERATION =
            "check";

    private static final String LOGIN_SUCCESS_CLEANUP_OPERATION =
            "login_success_cleanup";

    private static final String SUCCESS_OUTCOME =
            "success";

    private static final String ERROR_OUTCOME =
            "error";

    private static final String PUBLIC_LIMIT_MESSAGE =
            "Слишком много попыток входа. Попробуйте позже";

    private static final long CLEANUP_WARNING_INTERVAL_NANOS =
            TimeUnit.MINUTES.toNanos(1);

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final LoginRateLimitProperties properties;
    private final RateLimitKeyFactory keyFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final RateLimitMetrics metrics;

    private final AtomicLong nextCleanupWarningNanos =
            new AtomicLong();

    /**
     * Fail-closed: Redis недоступен — login блокируется с HTTP 503.
     *
     * <p>Email и IP резервируются одним атомарным Lua-скриптом.</p>
     */
    public void checkAllowed(
            String email,
            String ipAddress
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        LoginRateLimitKeys keys =
                createKeys(email, ipAddress);

        Timer.Sample sample =
                metrics.startRedisOperation();

        DualRateLimitResult result;

        try {
            result = rateLimiter.incrementBothAndCheck(
                    keys.emailKey(),
                    keys.emailMarkerKey(),
                    properties.effectiveEmailLimit(),
                    keys.ipKey(),
                    keys.ipMarkerKey(),
                    properties.effectiveIpLimit(),
                    properties.effectiveWindow()
            );

            finishRedisOperation(
                    sample,
                    CHECK_OPERATION,
                    SUCCESS_OUTCOME
            );
        } catch (RuntimeException exception) {
            finishRedisOperation(
                    sample,
                    CHECK_OPERATION,
                    ERROR_OUTCOME
            );

            metrics.recordUnavailable(
                    RATE_LIMIT_TYPE
            );

            throw new RateLimitUnavailableException(
                    "Redis login rate limit недоступен",
                    exception
            );
        }

        if (result.allowed()) {
            metrics.recordAllowed(
                    RATE_LIMIT_TYPE
            );

            return;
        }

        metrics.recordRejected(
                RATE_LIMIT_TYPE,
                rejectedDimension(
                        result.decision()
                )
        );

        if (result.notificationRequired()) {
            publishExceededEventBestEffort(
                    keys,
                    result
            );
        }

        throw new RateLimitExceededException(
                PUBLIC_LIMIT_MESSAGE,
                Duration.ofSeconds(
                        result.retryAfterSeconds()
                )
        );
    }

    /**
     * Вызывать только после полностью успешной аутентификации.
     *
     * <p>Email failures сбрасываются полностью, а IP counter уменьшается
     * ровно на одну предварительно зарезервированную успешную попытку.</p>
     */
    public void onLoginSuccess(
            String email,
            String ipAddress
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        LoginRateLimitKeys keys =
                createKeys(email, ipAddress);

        Timer.Sample sample =
                metrics.startRedisOperation();

        try {
            rateLimiter.resetFirstAndDecrementSecond(
                    keys.emailKey(),
                    keys.ipKey(),
                    keys.emailMarkerKey(),
                    keys.ipMarkerKey()
            );

            finishRedisOperation(
                    sample,
                    LOGIN_SUCCESS_CLEANUP_OPERATION,
                    SUCCESS_OUTCOME
            );
        } catch (RuntimeException exception) {
            finishRedisOperation(
                    sample,
                    LOGIN_SUCCESS_CLEANUP_OPERATION,
                    ERROR_OUTCOME
            );

            metrics.recordUnavailable(
                    RATE_LIMIT_TYPE
            );

            /*
             * Пользователь уже успешно вошёл. Ошибка cleanup не должна
             * превращать успешный login в HTTP 503.
             */
            warnCleanupFailureRateLimited(
                    exception
            );
        }
    }

    private LoginRateLimitKeys createKeys(
            String email,
            String ipAddress
    ) {
        String normalizedEmail =
                normalizeEmail(email);

        String normalizedIp =
                normalizeIp(ipAddress);

        String emailKey =
                keyFactory.loginEmail(
                        normalizedEmail
                );

        String ipKey =
                keyFactory.loginIp(
                        normalizedIp
                );

        return new LoginRateLimitKeys(
                normalizedEmail,
                normalizedIp,
                emailKey,
                ipKey,
                keyFactory.exceededMarker(
                        emailKey
                ),
                keyFactory.exceededMarker(
                        ipKey
                )
        );
    }

    private void finishRedisOperation(
            Timer.Sample sample,
            String operation,
            String outcome
    ) {
        metrics.finishRedisOperation(
                sample,
                RATE_LIMIT_TYPE,
                operation,
                outcome
        );
    }

    private void publishExceededEventBestEffort(
            LoginRateLimitKeys keys,
            DualRateLimitResult result
    ) {
        String notificationDimension =
                notificationDimension(result);

        Integer eventLimit =
                notificationLimit(result);

        Map<String, Object> details =
                buildEventDetails(
                        keys,
                        result,
                        notificationDimension
                );

        try {
            eventPublisher.publishEvent(
                    new RateLimitExceededEvent(
                            null,
                            null,
                            keys.normalizedEmail(),
                            null,
                            properties
                                    .effectiveAuditOrganizationId(),
                            loginEventType(result),
                            eventLimit,
                            formatWindow(
                                    properties.effectiveWindow()
                            ),
                            details
                    )
            );
        } catch (RuntimeException exception) {
            metrics.recordAuditPublishFailed(
                    RATE_LIMIT_TYPE
            );

            releaseNotificationMarkersBestEffort(
                    keys,
                    result,
                    exception
            );

            log.warn(
                    "Failed to publish login rate-limit event: "
                            + "dimension={}, decision={}",
                    notificationDimension,
                    result.decision(),
                    exception
            );
        }
    }

    private Map<String, Object> buildEventDetails(
            LoginRateLimitKeys keys,
            DualRateLimitResult result,
            String notificationDimension
    ) {
        return Map.ofEntries(
                Map.entry(
                        "source",
                        "LOGIN"
                ),
                Map.entry(
                        "dimension",
                        notificationDimension
                ),
                Map.entry(
                        "decision",
                        result.decision().name()
                ),
                Map.entry(
                        "email",
                        keys.normalizedEmail()
                ),
                Map.entry(
                        "emailFingerprint",
                        keyFactory.emailFingerprint(
                                keys.normalizedEmail()
                        )
                ),
                Map.entry(
                        "ipFingerprint",
                        keyFactory.ipFingerprint(
                                keys.normalizedIp()
                        )
                ),
                Map.entry(
                        "emailLimit",
                        properties.effectiveEmailLimit()
                ),
                Map.entry(
                        "ipLimit",
                        properties.effectiveIpLimit()
                ),
                Map.entry(
                        "emailCount",
                        result.firstCount()
                ),
                Map.entry(
                        "ipCount",
                        result.secondCount()
                ),
                Map.entry(
                        "emailTtlSeconds",
                        result.firstTtlSeconds()
                ),
                Map.entry(
                        "ipTtlSeconds",
                        result.secondTtlSeconds()
                ),
                Map.entry(
                        "retryAfterSeconds",
                        result.retryAfterSeconds()
                ),
                Map.entry(
                        "emailNotification",
                        result.firstExceededNotification()
                ),
                Map.entry(
                        "ipNotification",
                        result.secondExceededNotification()
                )
        );
    }

    private void releaseNotificationMarkersBestEffort(
            LoginRateLimitKeys keys,
            DualRateLimitResult result,
            RuntimeException publicationException
    ) {
        try {
            rateLimiter.releaseNotificationMarkers(
                    result,
                    keys.emailMarkerKey(),
                    keys.ipMarkerKey()
            );
        } catch (RuntimeException markerException) {
            publicationException.addSuppressed(
                    markerException
            );

            metrics.recordUnavailable(
                    RATE_LIMIT_TYPE
            );
        }
    }

    private String loginEventType(
            DualRateLimitResult result
    ) {
        if (result.firstExceededNotification()
                && result.secondExceededNotification()) {
            return "LOGIN_EMAIL_AND_IP";
        }

        if (result.firstExceededNotification()) {
            return "LOGIN_EMAIL";
        }

        return "LOGIN_IP";
    }

    private Integer notificationLimit(
            DualRateLimitResult result
    ) {
        if (result.firstExceededNotification()
                && !result.secondExceededNotification()) {
            return properties.effectiveEmailLimit();
        }

        if (result.secondExceededNotification()
                && !result.firstExceededNotification()) {
            return properties.effectiveIpLimit();
        }

        return null;
    }

    private String notificationDimension(
            DualRateLimitResult result
    ) {
        if (result.firstExceededNotification()
                && result.secondExceededNotification()) {
            return "BOTH";
        }

        if (result.firstExceededNotification()) {
            return "EMAIL";
        }

        return "IP";
    }

    private String rejectedDimension(
            RateLimitDecision decision
    ) {
        return switch (decision) {
            case FIRST_EXCEEDED -> "email";
            case SECOND_EXCEEDED -> "ip";
            case BOTH_EXCEEDED -> "both";
            case ALLOWED -> throw new IllegalArgumentException(
                    "ALLOWED не является отклонённым решением"
            );
        };
    }

    private String normalizeEmail(
            String email
    ) {
        return email == null || email.isBlank()
                ? "unknown"
                : email.trim()
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeIp(
            String ipAddress
    ) {
        return ipAddress == null || ipAddress.isBlank()
                ? "unknown"
                : ipAddress.trim();
    }

    private String formatWindow(
            Duration window
    ) {
        Objects.requireNonNull(
                window,
                "window не должен быть null"
        );

        if (window.toHoursPart() == 0
                && window.toMinutesPart() == 0) {
            return window.toSeconds() + "s";
        }

        if (window.toHoursPart() == 0
                && window.toSecondsPart() == 0) {
            return window.toMinutes() + "m";
        }

        if (window.toMinutesPart() == 0
                && window.toSecondsPart() == 0) {
            return window.toHours() + "h";
        }

        return window.toString();
    }

    private void warnCleanupFailureRateLimited(
            RuntimeException exception
    ) {
        long now = System.nanoTime();

        while (true) {
            long next =
                    nextCleanupWarningNanos.get();

            if (now - next < 0L) {
                return;
            }

            long candidate =
                    now + CLEANUP_WARNING_INTERVAL_NANOS;

            if (nextCleanupWarningNanos.compareAndSet(
                    next,
                    candidate
            )) {
                log.warn(
                        "Failed to compensate successful login "
                                + "in Redis rate limit",
                        exception
                );

                return;
            }

            now = System.nanoTime();
        }
    }

    private record LoginRateLimitKeys(
            String normalizedEmail,
            String normalizedIp,
            String emailKey,
            String ipKey,
            String emailMarkerKey,
            String ipMarkerKey
    ) {
        private LoginRateLimitKeys {
            Objects.requireNonNull(
                    normalizedEmail,
                    "normalizedEmail не должен быть null"
            );

            Objects.requireNonNull(
                    normalizedIp,
                    "normalizedIp не должен быть null"
            );

            Objects.requireNonNull(
                    emailKey,
                    "emailKey не должен быть null"
            );

            Objects.requireNonNull(
                    ipKey,
                    "ipKey не должен быть null"
            );

            Objects.requireNonNull(
                    emailMarkerKey,
                    "emailMarkerKey не должен быть null"
            );

            Objects.requireNonNull(
                    ipMarkerKey,
                    "ipMarkerKey не должен быть null"
            );
        }
    }
}