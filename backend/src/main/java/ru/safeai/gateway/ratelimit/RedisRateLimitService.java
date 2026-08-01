package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@EnableConfigurationProperties(
        AiMessageRateLimitProperties.class
)
public class RedisRateLimitService {

    private static final String RATE_LIMIT_TYPE =
            "ai_message";

    private static final String AI_MESSAGE_WINDOW =
            "1h";

    private static final Duration WINDOW =
            Duration.ofHours(1);

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final AiMessageRateLimitProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final RateLimitKeyFactory keyFactory;
    private final RateLimitMetrics metrics;

    public RedisRateLimitService(
            RedisFixedWindowRateLimiter rateLimiter,
            AiMessageRateLimitProperties properties,
            ApplicationEventPublisher eventPublisher,
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

        this.eventPublisher = Objects.requireNonNull(
                eventPublisher,
                "eventPublisher не должен быть null"
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
     * Fail-closed policy:
     * Redis failure blocks paid AI traffic with HTTP 503.
     * User and organization counters are checked atomically and are
     * incremented only when both dimensions allow the request.
     */
    public void checkAiMessageAllowed(
            SafeAiUserPrincipal user
    ) {
        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );

        if (!properties.isEnabled()) {
            return;
        }

        int userLimit = isAdminOrSuperAdmin(user)
                ? properties.effectiveAdminLimitPerHour()
                : properties.effectiveUserLimitPerHour();

        int organizationLimit =
                properties
                        .effectiveOrganizationLimitPerHour();

        String userKey =
                keyFactory.aiMessageUser(
                        user.getOrganizationId(),
                        user.getId()
                );

        String organizationKey =
                keyFactory.aiMessageOrganization(
                        user.getOrganizationId()
                );

        String userMarker =
                keyFactory.exceededMarker(userKey);

        String organizationMarker =
                keyFactory.exceededMarker(
                        organizationKey
                );

        Timer.Sample sample =
                metrics.startRedisOperation();

        DualRateLimitResult result;

        try {
            result = rateLimiter.tryIncrementBoth(
                    userKey,
                    userMarker,
                    userLimit,
                    organizationKey,
                    organizationMarker,
                    organizationLimit,
                    WINDOW
            );

            metrics.finishRedisOperation(
                    sample,
                    RATE_LIMIT_TYPE,
                    "check",
                    "success"
            );
        } catch (RuntimeException exception) {
            metrics.finishRedisOperation(
                    sample,
                    RATE_LIMIT_TYPE,
                    "check",
                    "error"
            );

            metrics.recordUnavailable(
                    RATE_LIMIT_TYPE
            );

            throw new RateLimitUnavailableException(
                    "Redis AI message rate limit недоступен",
                    exception
            );
        }

        if (result.allowed()) {
            metrics.recordAllowed(RATE_LIMIT_TYPE);
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
                    user,
                    userLimit,
                    organizationLimit,
                    result,
                    userMarker,
                    organizationMarker
            );
        }

        throw new RateLimitExceededException(
                publicMessage(
                        result.decision(),
                        userLimit,
                        organizationLimit
                ),
                Duration.ofSeconds(
                        result.retryAfterSeconds()
                )
        );
    }

    private void publishExceededEventBestEffort(
            SafeAiUserPrincipal user,
            int userLimit,
            int organizationLimit,
            DualRateLimitResult result,
            String userMarker,
            String organizationMarker
    ) {
        String notificationDimension =
                notificationDimension(result);

        Integer eventLimit =
                notificationLimit(
                        result,
                        userLimit,
                        organizationLimit
                );

        Map<String, Object> details = Map.ofEntries(
                Map.entry(
                        "source",
                        "AI_MESSAGE"
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
                        "window",
                        AI_MESSAGE_WINDOW
                ),
                Map.entry(
                        "userLimit",
                        userLimit
                ),
                Map.entry(
                        "organizationLimit",
                        organizationLimit
                ),
                Map.entry(
                        "userCount",
                        result.firstCount()
                ),
                Map.entry(
                        "organizationCount",
                        result.secondCount()
                ),
                Map.entry(
                        "userTtlSeconds",
                        result.firstTtlSeconds()
                ),
                Map.entry(
                        "organizationTtlSeconds",
                        result.secondTtlSeconds()
                ),
                Map.entry(
                        "retryAfterSeconds",
                        result.retryAfterSeconds()
                ),
                Map.entry(
                        "userNotification",
                        result.firstExceededNotification()
                ),
                Map.entry(
                        "organizationNotification",
                        result.secondExceededNotification()
                )
        );

        try {
            eventPublisher.publishEvent(
                    new RateLimitExceededEvent(
                            user.getId(),
                            user.getOrganizationId(),
                            user.getEmail(),
                            null,
                            user.getOrganizationId(),
                            aiEventType(result),
                            eventLimit,
                            AI_MESSAGE_WINDOW,
                            details
                    )
            );
        } catch (RuntimeException exception) {
            metrics.recordAuditPublishFailed(
                    RATE_LIMIT_TYPE
            );

            try {
                rateLimiter.releaseNotificationMarkers(
                        result,
                        userMarker,
                        organizationMarker
                );
            } catch (RuntimeException markerException) {
                exception.addSuppressed(markerException);
                metrics.recordUnavailable(
                        RATE_LIMIT_TYPE
                );
            }

            /*
             * Audit notification remains best effort. Publication failure
             * must not replace the intended HTTP 429 with HTTP 503.
             */
            log.warn(
                    "Failed to publish AI rate-limit event: "
                            + "userId={}, organizationId={}, "
                            + "dimension={}, decision={}",
                    user.getId(),
                    user.getOrganizationId(),
                    notificationDimension,
                    result.decision(),
                    exception
            );
        }
    }

    private String aiEventType(
            DualRateLimitResult result
    ) {
        if (result.firstExceededNotification()
                && result.secondExceededNotification()) {
            return "AI_MESSAGE_USER_AND_ORGANIZATION";
        }

        if (result.firstExceededNotification()) {
            return "AI_MESSAGE_USER";
        }

        return "AI_MESSAGE_ORGANIZATION";
    }

    private Integer notificationLimit(
            DualRateLimitResult result,
            int userLimit,
            int organizationLimit
    ) {
        if (result.firstExceededNotification()
                && !result.secondExceededNotification()) {
            return userLimit;
        }

        if (result.secondExceededNotification()
                && !result.firstExceededNotification()) {
            return organizationLimit;
        }

        /*
         * BOTH имеет два самостоятельных лимита.
         * Не публикуем неоднозначное агрегированное значение.
         */
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
            return "USER";
        }

        return "ORGANIZATION";
    }

    private String rejectedDimension(
            RateLimitDecision decision
    ) {
        return switch (decision) {
            case FIRST_EXCEEDED -> "user";
            case SECOND_EXCEEDED -> "organization";
            case BOTH_EXCEEDED -> "both";
            case ALLOWED -> "none";
        };
    }

    private String publicMessage(
            RateLimitDecision decision,
            int userLimit,
            int organizationLimit
    ) {
        return switch (decision) {
            case FIRST_EXCEEDED ->
                    "Превышен лимит AI-запросов пользователя. "
                            + "Лимит: "
                            + userLimit
                            + " в час";

            case SECOND_EXCEEDED ->
                    "Превышен лимит AI-запросов организации. "
                            + "Лимит: "
                            + organizationLimit
                            + " в час";

            case BOTH_EXCEEDED ->
                    "Превышены лимиты AI-запросов пользователя "
                            + "и организации";

            case ALLOWED -> throw new IllegalArgumentException(
                    "ALLOWED не является превышением лимита"
            );
        };
    }

    private boolean isAdminOrSuperAdmin(
            SafeAiUserPrincipal user
    ) {
        return user.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority ->
                        "ROLE_ADMIN".equals(authority)
                                || "ROLE_SUPER_ADMIN"
                                .equals(authority)
                );
    }
}
