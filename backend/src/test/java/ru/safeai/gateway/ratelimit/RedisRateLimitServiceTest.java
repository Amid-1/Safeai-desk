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

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RedisRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RedisRateLimitService(
                rateLimiter,
                new AiMessageRateLimitProperties(
                        true,
                        20,
                        100
                ),
                eventPublisher
        );
    }

    @Test
    void checkAiMessageAllowed_whenUserWithinLimit_shouldPass() {
        when(rateLimiter.incrementAndGet(
                "rate-limit:ai-message:user:" + USER_ID,
                Duration.ofHours(1)
        )).thenReturn(new RateLimitResult(20L, 3600L));

        assertThatCode(() -> service.checkAiMessageAllowed(userPrincipal()))
                .doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void checkAiMessageAllowed_whenAdminAboveUserLimitButWithinAdminLimit_shouldPass() {
        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(20L, 3600L));

        assertThatCode(() -> service.checkAiMessageAllowed(adminPrincipal()))
                .doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void checkAiMessageAllowed_whenUserLimitExceededFirstTime_shouldPublishEventAndThrow() {
        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(20L, 3600L));

        assertThatThrownBy(() -> service.checkAiMessageAllowed(userPrincipal()))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Превышен лимит AI-запросов");

        verify(eventPublisher).publishEvent(any(RateLimitExceededEvent.class));
    }

    @Test
    void checkAiMessageAllowed_whenUserAlreadyExceededLimit_shouldNotPublishEventAgain() {
        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenReturn(new RateLimitResult(20L, 3600L));

        assertThatThrownBy(() -> service.checkAiMessageAllowed(userPrincipal()))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void checkAiMessageAllowed_whenDisabled_shouldDoNothing() {
        RedisRateLimitService disabledService = new RedisRateLimitService(
                rateLimiter,
                new AiMessageRateLimitProperties(
                        false,
                        20,
                        100
                ),
                eventPublisher
        );

        disabledService.checkAiMessageAllowed(userPrincipal());

        verifyNoInteractions(rateLimiter);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void checkAiMessageAllowed_whenRedisFails_shouldThrowRateLimitUnavailableException() {
        when(rateLimiter.incrementAndGet(any(String.class), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.checkAiMessageAllowed(userPrincipal()))
                .isInstanceOf(RateLimitUnavailableException.class)
                .hasMessageContaining("Redis rate limit недоступен");

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
}