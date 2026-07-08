package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisRateLimitServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final String AI_USER_MESSAGE_KEY = "test-ai-user-message-key";
    private static final String AI_ORGANIZATION_MESSAGE_KEY = "test-ai-organization-message-key";

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
                new AiMessageRateLimitProperties(true, 20, 100, 1000),
                eventPublisher,
                keyFactory
        );
    }

    @Test
    void checkAiMessageAllowed_whenUserWithinLimit_shouldPass() {
        mockUserAndOrganizationKeys();

        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(20L, 3600L))
                .thenReturn(new RateLimitResult(200L, 3600L));

        assertThatCode(() -> service.checkAiMessageAllowed(userPrincipal()))
                .doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void checkAiMessageAllowed_whenAdminAboveUserLimitButWithinAdminLimit_shouldPass() {
        mockUserAndOrganizationKeys();

        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(80L, 3600L))
                .thenReturn(new RateLimitResult(200L, 3600L));

        assertThatCode(() -> service.checkAiMessageAllowed(adminPrincipal()))
                .doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void checkAiMessageAllowed_whenUserLimitExceededFirstTime_shouldPublishEventAndThrow() {
        when(keyFactory.aiMessageUser(USER_ID))
                .thenReturn(AI_USER_MESSAGE_KEY);

        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(21L, 3600L));

        assertThatThrownBy(() -> service.checkAiMessageAllowed(userPrincipal()))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Превышен лимит AI-запросов пользователя");

        verify(eventPublisher).publishEvent(any(RateLimitExceededEvent.class));
        verify(keyFactory, never()).aiMessageOrganization(any(UUID.class));
    }

    @Test
    void checkAiMessageAllowed_whenUserAlreadyExceededLimit_shouldNotPublishEventAgain() {
        when(keyFactory.aiMessageUser(USER_ID))
                .thenReturn(AI_USER_MESSAGE_KEY);

        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(22L, 3600L));

        assertThatThrownBy(() -> service.checkAiMessageAllowed(userPrincipal()))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(eventPublisher);
        verify(keyFactory, never()).aiMessageOrganization(any(UUID.class));
    }

    @Test
    void checkAiMessageAllowed_whenOrganizationLimitExceededFirstTime_shouldPublishEventAndThrow() {
        mockUserAndOrganizationKeys();

        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(20L, 3600L))
                .thenReturn(new RateLimitResult(1001L, 3600L));

        assertThatThrownBy(() -> service.checkAiMessageAllowed(userPrincipal()))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Превышен лимит AI-запросов организации");

        verify(eventPublisher).publishEvent(any(RateLimitExceededEvent.class));
    }

    @Test
    void checkAiMessageAllowed_whenDisabled_shouldDoNothing() {
        RedisRateLimitService disabledService = new RedisRateLimitService(
                rateLimiter,
                new AiMessageRateLimitProperties(false, 20, 100, 1000),
                eventPublisher,
                keyFactory
        );

        disabledService.checkAiMessageAllowed(userPrincipal());

        verifyNoInteractions(rateLimiter);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(keyFactory);
    }

    @Test
    void checkAiMessageAllowed_whenRedisFails_shouldThrowRateLimitUnavailableException() {
        when(keyFactory.aiMessageUser(USER_ID))
                .thenReturn(AI_USER_MESSAGE_KEY);

        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.checkAiMessageAllowed(userPrincipal()))
                .isInstanceOf(RateLimitUnavailableException.class)
                .hasMessageContaining("Redis AI message rate limit недоступен");

        verifyNoInteractions(eventPublisher);
    }

    private void mockUserAndOrganizationKeys() {
        when(keyFactory.aiMessageUser(USER_ID))
                .thenReturn(AI_USER_MESSAGE_KEY);

        when(keyFactory.aiMessageOrganization(ORGANIZATION_ID))
                .thenReturn(AI_ORGANIZATION_MESSAGE_KEY);
    }

    @Test
    void checkAiMessageAllowed_whenSuperAdminAboveUserLimitButWithinAdminLimit_shouldPass() {
        mockUserAndOrganizationKeys();

        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(80L, 3600L))
                .thenReturn(new RateLimitResult(200L, 3600L));

        assertThatCode(() -> service.checkAiMessageAllowed(superAdminPrincipal()))
                .doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    private SafeAiUserPrincipal userPrincipal() {
        return principal("ROLE_USER");
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return principal("ROLE_ADMIN");
    }

    private SafeAiUserPrincipal principal(String role) {
        return new SafeAiUserPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "user@test.com",
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority(role))
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return principal("ROLE_SUPER_ADMIN");
    }
}