package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginRateLimitServiceTest {

    private static final String EMAIL = "admin@test.com";
    private static final String IP = "127.0.0.1";

    private static final String EMAIL_KEY = "safeai:local:rate-limit:login:email:test-hash";
    private static final String IP_KEY = "safeai:local:rate-limit:login:ip:test-hash";

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    @Mock
    private RateLimitKeyFactory keyFactory;

    private LoginRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new LoginRateLimitService(
                rateLimiter,
                new LoginRateLimitProperties(
                        true,
                        10,
                        30,
                        Duration.ofMinutes(10)
                ),
                keyFactory
        );
    }

    @Test
    void checkAllowed_whenWithinLimits_shouldIncrementEmailAndIpKeys() {
        when(keyFactory.loginEmail(EMAIL)).thenReturn(EMAIL_KEY);
        when(keyFactory.loginIp(IP)).thenReturn(IP_KEY);

        when(rateLimiter.incrementAndGet(anyString(), any(Duration.class)))
                .thenReturn(new RateLimitResult(1L, 600L));

        service.checkAllowed(" Admin@Test.COM ", IP);

        verify(keyFactory).loginEmail(EMAIL);
        verify(keyFactory).loginIp(IP);

        verify(rateLimiter).incrementAndGet(EMAIL_KEY, Duration.ofMinutes(10));
        verify(rateLimiter).incrementAndGet(IP_KEY, Duration.ofMinutes(10));

        verifyNoMoreInteractions(rateLimiter);
    }

    @Test
    void checkAllowed_whenEmailLimitExceeded_shouldThrowRateLimitExceededException() {
        when(keyFactory.loginEmail(EMAIL)).thenReturn(EMAIL_KEY);

        when(rateLimiter.incrementAndGet(EMAIL_KEY, Duration.ofMinutes(10)))
                .thenReturn(new RateLimitResult(11L, 600L));

        assertThatThrownBy(() -> service.checkAllowed(EMAIL, IP))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("email");

        verify(keyFactory).loginEmail(EMAIL);
        verify(keyFactory, never()).loginIp(anyString());

        verify(rateLimiter).incrementAndGet(EMAIL_KEY, Duration.ofMinutes(10));
        verifyNoMoreInteractions(rateLimiter);
    }

    @Test
    void checkAllowed_whenIpLimitExceeded_shouldThrowRateLimitExceededException() {
        when(keyFactory.loginEmail(EMAIL)).thenReturn(EMAIL_KEY);
        when(keyFactory.loginIp(IP)).thenReturn(IP_KEY);

        when(rateLimiter.incrementAndGet(EMAIL_KEY, Duration.ofMinutes(10)))
                .thenReturn(new RateLimitResult(1L, 600L));

        when(rateLimiter.incrementAndGet(IP_KEY, Duration.ofMinutes(10)))
                .thenReturn(new RateLimitResult(31L, 600L));

        assertThatThrownBy(() -> service.checkAllowed(EMAIL, IP))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("IP");

        verify(rateLimiter).incrementAndGet(EMAIL_KEY, Duration.ofMinutes(10));
        verify(rateLimiter).incrementAndGet(IP_KEY, Duration.ofMinutes(10));
    }

    @Test
    void checkAllowed_whenDisabled_shouldDoNothing() {
        LoginRateLimitService disabledService = new LoginRateLimitService(
                rateLimiter,
                new LoginRateLimitProperties(
                        false,
                        10,
                        30,
                        Duration.ofMinutes(10)
                ),
                keyFactory
        );

        disabledService.checkAllowed(EMAIL, IP);

        verifyNoInteractions(rateLimiter);
        verifyNoInteractions(keyFactory);
    }

    @Test
    void checkAllowed_whenRedisFails_shouldThrowRateLimitUnavailableException() {
        when(keyFactory.loginEmail(EMAIL)).thenReturn(EMAIL_KEY);

        when(rateLimiter.incrementAndGet(EMAIL_KEY, Duration.ofMinutes(10)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.checkAllowed(EMAIL, IP))
                .isInstanceOf(RateLimitUnavailableException.class)
                .hasMessageContaining("Redis login rate limit недоступен");

        verifyNoInteractionsAfterRedisFailure();
    }

    @Test
    void resetEmailLimit_shouldDeleteHashedEmailKey() {
        when(keyFactory.loginEmail(EMAIL)).thenReturn(EMAIL_KEY);

        service.resetEmailLimit(" Admin@Test.COM ");

        verify(keyFactory).loginEmail(EMAIL);
        verify(rateLimiter).reset(EMAIL_KEY);
    }

    @Test
    void checkAllowed_whenDisabled_shouldNotRequireKeys() {
        LoginRateLimitService disabledService = new LoginRateLimitService(
                rateLimiter,
                new LoginRateLimitProperties(
                        false,
                        10,
                        30,
                        Duration.ofMinutes(10)
                ),
                keyFactory
        );

        assertThatCode(() -> disabledService.checkAllowed(" Admin@Test.COM ", IP))
                .doesNotThrowAnyException();

        verifyNoInteractions(keyFactory);
        verifyNoInteractions(rateLimiter);
    }

    private void verifyNoInteractionsAfterRedisFailure() {
        verify(keyFactory).loginEmail(EMAIL);
        verify(keyFactory, never()).loginIp(anyString());
        verify(rateLimiter).incrementAndGet(EMAIL_KEY, Duration.ofMinutes(10));
        verifyNoMoreInteractions(rateLimiter);
    }
}