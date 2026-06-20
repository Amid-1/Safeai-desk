package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginRateLimitServiceTest {

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

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
                )
        );
    }

    @Test
    void checkAllowed_whenWithinLimits_shouldIncrementEmailAndIpKeys() {
        when(rateLimiter.incrementAndGet(anyString(), any(Duration.class)))
                .thenReturn(1L);

        service.checkAllowed(" Admin@Test.COM ", "127.0.0.1");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        verify(rateLimiter, times(2))
                .incrementAndGet(keyCaptor.capture(), eq(Duration.ofMinutes(10)));

        List<String> keys = keyCaptor.getAllValues();

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).startsWith("rate-limit:login:email:");
        assertThat(keys.get(1)).startsWith("rate-limit:login:ip:");

        assertThat(keys.get(0)).doesNotContain("admin@test.com");
        assertThat(keys.get(1)).doesNotContain("127.0.0.1");
    }

    @Test
    void checkAllowed_whenEmailLimitExceeded_shouldThrowRateLimitExceededException() {
        when(rateLimiter.incrementAndGet(anyString(), any(Duration.class)))
                .thenReturn(11L);

        assertThatThrownBy(() -> service.checkAllowed("admin@test.com", "127.0.0.1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("email");

        verify(rateLimiter, times(1))
                .incrementAndGet(anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test
    void checkAllowed_whenIpLimitExceeded_shouldThrowRateLimitExceededException() {
        when(rateLimiter.incrementAndGet(anyString(), any(Duration.class)))
                .thenReturn(1L, 31L);

        assertThatThrownBy(() -> service.checkAllowed("admin@test.com", "127.0.0.1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("IP");

        verify(rateLimiter, times(2))
                .incrementAndGet(anyString(), eq(Duration.ofMinutes(10)));
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
                )
        );

        disabledService.checkAllowed("admin@test.com", "127.0.0.1");

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void checkAllowed_whenRedisFails_shouldThrowRateLimitUnavailableException() {
        when(rateLimiter.incrementAndGet(anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.checkAllowed("admin@test.com", "127.0.0.1"))
                .isInstanceOf(RateLimitUnavailableException.class)
                .hasMessageContaining("Redis login rate limit недоступен");
    }

    @Test
    void resetEmailLimit_shouldDeleteHashedEmailKey() {
        service.resetEmailLimit(" Admin@Test.COM ");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        verify(rateLimiter).reset(keyCaptor.capture());

        String key = keyCaptor.getValue();

        assertThat(key).startsWith("rate-limit:login:email:");
        assertThat(key).doesNotContain("admin@test.com");
        assertThat(key.length()).isGreaterThan("rate-limit:login:email:".length());
    }
}