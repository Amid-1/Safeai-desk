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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRateLimitServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final String USER_KEY =
            "safeai:test:rate-limit:ai-message:user";

    private static final String ORGANIZATION_KEY =
            "safeai:test:rate-limit:ai-message:organization";

    private static final Duration WINDOW =
            Duration.ofHours(1);

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private RateLimitKeyFactory keyFactory;

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
                keyFactory
        );
    }

    @Test
    void userWithinLimitsPassesWithAtomicDualCheck() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                20,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenReturn(allowedResult(20));

        assertThatCode(() ->
                service.checkAiMessageAllowed(userPrincipal())
        ).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void adminUsesAdminLimit() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                100,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenReturn(allowedResult(80));

        assertThatCode(() ->
                service.checkAiMessageAllowed(adminPrincipal())
        ).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void superAdminUsesAdminLimit() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                100,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenReturn(allowedResult(80));

        assertThatCode(() ->
                service.checkAiMessageAllowed(superAdminPrincipal())
        ).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void userLimitExceededPublishesEventAndThrows() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                20,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                20,
                200,
                3_600,
                3_600,
                true
        ));

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(userPrincipal())
        )
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining(
                        "Превышен лимит AI-запросов пользователя"
                );

        ArgumentCaptor<RateLimitExceededEvent> captor =
                ArgumentCaptor.forClass(
                        RateLimitExceededEvent.class
                );

        verify(eventPublisher)
                .publishEvent(captor.capture());

        assertThat(captor.getValue().type())
                .isEqualTo("AI_MESSAGE_USER");

        assertThat(captor.getValue().limit())
                .isEqualTo(20);

        assertThat(captor.getValue().details())
                .containsEntry(
                        "decision",
                        "FIRST_EXCEEDED"
                );
    }

    @Test
    void repeatedUserLimitExceededDoesNotPublishAgain() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                20,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                20,
                200,
                3_600,
                3_600,
                false
        ));

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(userPrincipal())
        ).isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void organizationLimitExceededThrowsOrganizationMessage() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                20,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.SECOND_EXCEEDED,
                5,
                1_000,
                3_600,
                1_800,
                false
        ));

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(userPrincipal())
        )
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining(
                        "Превышен лимит AI-запросов организации"
                );

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void bothLimitsExceededReturnsCombinedMessage() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                20,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.BOTH_EXCEEDED,
                20,
                1_000,
                900,
                1_800,
                false
        ));

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(userPrincipal())
        )
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining(
                        "Превышены лимиты AI-запросов пользователя "
                                + "и организации"
                );

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void eventPublishingFailureDoesNotChange429Into503() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                20,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                20,
                100,
                3_600,
                3_600,
                true
        ));

        doThrow(new RuntimeException("audit unavailable"))
                .when(eventPublisher)
                .publishEvent(any(Object.class));

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(userPrincipal())
        )
                .isInstanceOf(RateLimitExceededException.class)
                .isNotInstanceOf(
                        RateLimitUnavailableException.class
                );
    }

    @Test
    void redisFailureReturnsRateLimitUnavailable() {
        stubKeys();

        when(rateLimiter.tryIncrementBoth(
                USER_KEY,
                20,
                ORGANIZATION_KEY,
                1_000,
                WINDOW
        )).thenThrow(
                new RuntimeException("Redis unavailable")
        );

        assertThatThrownBy(() ->
                service.checkAiMessageAllowed(userPrincipal())
        )
                .isInstanceOf(
                        RateLimitUnavailableException.class
                )
                .hasMessageContaining(
                        "Redis AI message rate limit недоступен"
                );

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
                        keyFactory
                );

        disabledService.checkAiMessageAllowed(
                userPrincipal()
        );

        verifyNoInteractions(
                rateLimiter,
                eventPublisher,
                keyFactory
        );
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
                false
        );
    }

    private void stubKeys() {
        when(keyFactory.aiMessageUser(USER_ID))
                .thenReturn(USER_KEY);

        when(keyFactory.aiMessageOrganization(
                ORGANIZATION_ID
        )).thenReturn(ORGANIZATION_KEY);
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
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "user@test.com",
                0L,
                List.of(
                        new SimpleGrantedAuthority(role)
                )
        );
    }
}
