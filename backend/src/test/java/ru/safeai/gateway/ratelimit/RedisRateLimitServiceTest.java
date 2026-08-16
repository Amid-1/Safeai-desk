package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRateLimitServiceTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final String USER_KEY =
            "safeai:test:v1:rate-limit:{ai-org}:user:hash";

    private static final String ORGANIZATION_KEY =
            "safeai:test:v1:rate-limit:{ai-org}:organization";

    private static final String USER_MARKER =
            USER_KEY + ":exceeded";

    private static final String ORGANIZATION_MARKER =
            ORGANIZATION_KEY + ":exceeded";

    private static final Duration WINDOW =
            Duration.ofHours(1);

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private RateLimitKeyFactory keyFactory;

    @Mock
    private RateLimitMetrics metrics;

    private RedisRateLimitService service;

    @BeforeEach
void setUp() {
    service = new RedisRateLimitService(
            rateLimiter,
            new AiMessageRateLimitProperties(
                    true,
                    20,
                    100,
                    1_000
            ),
            eventPublisher,
            keyFactory,
            metrics
    );
}

    @Test
    void userWithinLimitsPassesWithAtomicDualCheck() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                USER_MARKER,
                20,
                ORGANIZATION_KEY,
                ORGANIZATION_MARKER,
                1_000,
                WINDOW
        )).thenReturn(allowedResult(20));

        assertThatCode(() ->
                service.checkAiMessageAllowed(
                        userPrincipal()
                )
        ).doesNotThrowAnyException();

        verify(metrics)
                .recordAllowed("ai_message");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void adminAndSuperAdminUseAdminLimit() {
        for (SafeAiUserPrincipal principal
                : List.of(
                        adminPrincipal(),
                        superAdminPrincipal()
                )) {
            stubKeys();

            when(rateLimiter.tryIncrementBoth(
                    USER_KEY,
                    USER_MARKER,
                    100,
                    ORGANIZATION_KEY,
                    ORGANIZATION_MARKER,
                    1_000,
                    WINDOW
            )).thenReturn(allowedResult(80));

            service.checkAiMessageAllowed(principal);

            verify(rateLimiter).tryIncrementBoth(
                    USER_KEY,
                    USER_MARKER,
                    100,
                    ORGANIZATION_KEY,
                    ORGANIZATION_MARKER,
                    1_000,
                    WINDOW
            );

            org.mockito.Mockito.clearInvocations(
                    rateLimiter,
                    keyFactory,
                    metrics
            );
        }
    }

    @Test
    void userLimitExceededPublishesUserEventAndUsesUserTtl() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                USER_MARKER,
                20,
                ORGANIZATION_KEY,
                ORGANIZATION_MARKER,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                20,
                200,
                900,
                1_800,
                true,
                false
        ));

        RateLimitExceededException exception =
                org.assertj.core.api.Assertions
                        .catchThrowableOfType(
                                RateLimitExceededException.class,
                                () -> service
                                        .checkAiMessageAllowed(
                                                userPrincipal()
                                        )
                        );

        assertThat(exception.getRetryAfterSeconds())
                .isEqualTo(900);

        ArgumentCaptor<RateLimitExceededEvent> captor =
                ArgumentCaptor.forClass(
                        RateLimitExceededEvent.class
                );

        verify(eventPublisher)
                .publishEvent(captor.capture());

        RateLimitExceededEvent event =
                captor.getValue();

        assertThat(event.type())
                .isEqualTo("AI_MESSAGE_USER");
        assertThat(event.limit())
                .isEqualTo(20);
        assertThat(event.details())
                .containsEntry("userLimit", 20)
                .containsEntry(
                        "organizationLimit",
                        1_000
                )
                .containsEntry(
                        "dimension",
                        "USER"
                );
    }

    @Test
    void organizationLimitExceededUsesOrganizationTtl() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                USER_MARKER,
                20,
                ORGANIZATION_KEY,
                ORGANIZATION_MARKER,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.SECOND_EXCEEDED,
                5,
                1_000,
                3_600,
                1_800,
                false,
                true
        ));

        RateLimitExceededException exception =
                org.assertj.core.api.Assertions
                        .catchThrowableOfType(
                                RateLimitExceededException.class,
                                () -> service
                                        .checkAiMessageAllowed(
                                                userPrincipal()
                                        )
                        );

        assertThat(exception.getRetryAfterSeconds())
                .isEqualTo(1_800);

        verify(metrics).recordRejected(
                "ai_message",
                "organization"
        );
    }

    @Test
    void bothEventContainsBothLimitsAndNoAmbiguousSingleLimit() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                USER_MARKER,
                20,
                ORGANIZATION_KEY,
                ORGANIZATION_MARKER,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.BOTH_EXCEEDED,
                20,
                1_000,
                900,
                1_800,
                true,
                true
        ));

        RateLimitExceededException exception =
                org.assertj.core.api.Assertions
                        .catchThrowableOfType(
                                RateLimitExceededException.class,
                                () -> service
                                        .checkAiMessageAllowed(
                                                userPrincipal()
                                        )
                        );

        assertThat(exception.getRetryAfterSeconds())
                .isEqualTo(1_800);

        ArgumentCaptor<RateLimitExceededEvent> captor =
                ArgumentCaptor.forClass(
                        RateLimitExceededEvent.class
                );

        verify(eventPublisher)
                .publishEvent(captor.capture());

        RateLimitExceededEvent event =
                captor.getValue();

        assertThat(event.type())
                .isEqualTo(
                        "AI_MESSAGE_USER_AND_ORGANIZATION"
                );

        assertThat(event.limit()).isNull();

        assertThat(event.details())
                .containsEntry("userLimit", 20)
                .containsEntry(
                        "organizationLimit",
                        1_000
                )
                .containsEntry("dimension", "BOTH");
    }

    @Test
    void repeatedExceedWithoutNewMarkerDoesNotPublishAgain() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                USER_MARKER,
                20,
                ORGANIZATION_KEY,
                ORGANIZATION_MARKER,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                20,
                100,
                3_600,
                3_600,
                false,
                false
        ));

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(
                        userPrincipal()
                )
        ).isInstanceOf(
                RateLimitExceededException.class
        );

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void eventPublicationFailureReleasesCreatedMarkersAndKeeps429() {
        stubKeys();

        DualRateLimitResult result =
                new DualRateLimitResult(
                        RateLimitDecision.BOTH_EXCEEDED,
                        20,
                        1_000,
                        900,
                        1_800,
                        true,
                        true
                );

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                USER_MARKER,
                20,
                ORGANIZATION_KEY,
                ORGANIZATION_MARKER,
                1_000,
                WINDOW
        )).thenReturn(result);

        doThrow(new RuntimeException("audit unavailable"))
                .when(eventPublisher)
                .publishEvent(any());

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(
                        userPrincipal()
                )
        )
                .isInstanceOf(
                        RateLimitExceededException.class
                )
                .isNotInstanceOf(
                        RateLimitUnavailableException.class
                );

        verify(rateLimiter)
                .releaseNotificationMarkers(
                        result,
                        USER_MARKER,
                        ORGANIZATION_MARKER
                );

        verify(metrics)
                .recordAuditPublishFailed("ai_message");
    }

    @Test
    void redisFailureReturnsRateLimitUnavailable() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                USER_MARKER,
                20,
                ORGANIZATION_KEY,
                ORGANIZATION_MARKER,
                1_000,
                WINDOW
        )).thenThrow(
                new RuntimeException("Redis unavailable")
        );

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(
                        userPrincipal()
                )
        )
                .isInstanceOf(
                        RateLimitUnavailableException.class
                )
                .hasMessageContaining(
                        "Redis AI message rate limit недоступен"
                );

        verify(metrics)
                .recordUnavailable("ai_message");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void disabledLimiterDoesNothing() {
        RedisRateLimitService disabledService =
                new RedisRateLimitService(
                        rateLimiter,
                        new AiMessageRateLimitProperties(
                                false,
                                20,
                                100,
                                1_000
                        ),
                        eventPublisher,
                        keyFactory,
                        metrics
                );

        disabledService.checkAiMessageAllowed(
                userPrincipal()
        );

        verifyNoInteractions(
                rateLimiter,
                eventPublisher,
                keyFactory
        );

        verify(metrics, never())
                .startRedisOperation();
    }

    private DualRateLimitResult allowedResult(
            long userCount
    ) {
        return new DualRateLimitResult(
                RateLimitDecision.ALLOWED,
                userCount,
                200,
                3_600,
                3_600,
                false,
                false
        );
    }

    private void stubKeys() {
        when(keyFactory.aiMessageUser(
                ORGANIZATION_ID,
                USER_ID
        )).thenReturn(USER_KEY);

        when(keyFactory.aiMessageOrganization(
                ORGANIZATION_ID
        )).thenReturn(ORGANIZATION_KEY);

        when(keyFactory.exceededMarker(USER_KEY))
                .thenReturn(USER_MARKER);

        when(keyFactory.exceededMarker(
                ORGANIZATION_KEY
        )).thenReturn(ORGANIZATION_MARKER);
    }

    private SafeAiUserPrincipal userPrincipal() {
        return principal("ROLE_USER");
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return principal("ROLE_ADMIN");
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return principal("ROLE_SUPER_ADMIN");
    }

    private SafeAiUserPrincipal principal(
            String role
    ) {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        role
                                )
                        )
                );
    }
}
