package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshRateLimitServiceTest {

    private static final String IP =
            "203.0.113.10";

    private static final String KEY =
            "safeai:test:v1:rate-limit:refresh:ip:hash";

    private static final Duration WINDOW =
            Duration.ofMinutes(1);

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    @Mock
    private RateLimitKeyFactory keyFactory;

    @Mock
    private RateLimitMetrics metrics;

    @Mock
    private Timer.Sample timerSample;

    private RefreshRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RefreshRateLimitService(
                rateLimiter,
                enabledProperties(),
                keyFactory,
                metrics
        );
    }

    @Test
    void disabledLimiterDoesNotTouchRedisOrMetrics() {
        RefreshRateLimitService disabledService =
                new RefreshRateLimitService(
                        rateLimiter,
                        new RefreshRateLimitProperties(
                                false,
                                3,
                                WINDOW
                        ),
                        keyFactory,
                        metrics
                );

        assertThatCode(() ->
                disabledService.checkAllowed(IP)
        ).doesNotThrowAnyException();

        verifyNoInteractions(
                rateLimiter,
                keyFactory,
                metrics
        );
    }

    @Test
    void requestAtLimitIsAllowedAndRecordsMetrics() {
        when(keyFactory.refreshIp(IP))
                .thenReturn(KEY);

        when(metrics.startRedisOperation())
                .thenReturn(timerSample);

        when(rateLimiter.incrementAndGet(
                KEY,
                WINDOW
        )).thenReturn(
                new RateLimitResult(
                        3L,
                        37L
                )
        );

        assertThatCode(() ->
                service.checkAllowed(
                        "  " + IP + "  "
                )
        ).doesNotThrowAnyException();

        verify(keyFactory)
                .refreshIp(IP);

        verify(rateLimiter)
                .incrementAndGet(
                        KEY,
                        WINDOW
                );

        verify(metrics)
                .finishRedisOperation(
                        timerSample,
                        "refresh",
                        "check",
                        "success"
                );

        verify(metrics)
                .recordAllowed(
                        "refresh"
                );

        verify(metrics, never())
                .recordRejected(
                        "refresh",
                        "ip"
                );

        verify(metrics, never())
                .recordUnavailable(
                        "refresh"
                );
    }

    @Test
    void requestAboveLimitReturns429ContractWithRedisTtl() {
        when(keyFactory.refreshIp(IP))
                .thenReturn(KEY);

        when(metrics.startRedisOperation())
                .thenReturn(timerSample);

        when(rateLimiter.incrementAndGet(
                KEY,
                WINDOW
        )).thenReturn(
                new RateLimitResult(
                        4L,
                        42L
                )
        );

        assertThatThrownBy(() ->
                service.checkAllowed(IP)
        )
                .isInstanceOf(
                        RateLimitExceededException.class
                )
                .satisfies(throwable -> {
                    RateLimitExceededException exception =
                            (RateLimitExceededException) throwable;

                    assertThat(
                            exception.getRetryAfterSeconds()
                    ).isEqualTo(42L);

                    assertThat(
                            exception.getPublicMessage()
                    ).contains(
                            "обновления сессии"
                    );
                });

        verify(metrics)
                .finishRedisOperation(
                        timerSample,
                        "refresh",
                        "check",
                        "success"
                );

        verify(metrics)
                .recordRejected(
                        "refresh",
                        "ip"
                );

        verify(metrics, never())
                .recordAllowed(
                        "refresh"
                );

        verify(metrics, never())
                .recordUnavailable(
                        "refresh"
                );
    }

    @Test
    void redisFailureFailsClosedAsRateLimitUnavailable() {
        when(keyFactory.refreshIp(IP))
                .thenReturn(KEY);

        when(metrics.startRedisOperation())
                .thenReturn(timerSample);

        when(rateLimiter.incrementAndGet(
                KEY,
                WINDOW
        )).thenThrow(
                new IllegalStateException(
                        "redis unavailable"
                )
        );

        assertThatThrownBy(() ->
                service.checkAllowed(IP)
        )
                .isInstanceOf(
                        RateLimitUnavailableException.class
                )
                .hasMessageContaining(
                        "Redis refresh rate limit"
                );

        verify(metrics)
                .finishRedisOperation(
                        timerSample,
                        "refresh",
                        "check",
                        "error"
                );

        verify(metrics)
                .recordUnavailable(
                        "refresh"
                );

        verify(metrics, never())
                .recordAllowed(
                        "refresh"
                );

        verify(metrics, never())
                .recordRejected(
                        "refresh",
                        "ip"
                );
    }

    @Test
    void blankIpUsesStableUnknownIdentity() {
        when(keyFactory.refreshIp("unknown"))
                .thenReturn(KEY);

        when(metrics.startRedisOperation())
                .thenReturn(timerSample);

        when(rateLimiter.incrementAndGet(
                KEY,
                WINDOW
        )).thenReturn(
                new RateLimitResult(
                        1L,
                        60L
                )
        );

        service.checkAllowed("   ");

        verify(keyFactory)
                .refreshIp("unknown");
    }

    @Test
    void nullIpUsesStableUnknownIdentity() {
        when(keyFactory.refreshIp("unknown"))
                .thenReturn(KEY);

        when(metrics.startRedisOperation())
                .thenReturn(timerSample);

        when(rateLimiter.incrementAndGet(
                KEY,
                WINDOW
        )).thenReturn(
                new RateLimitResult(
                        1L,
                        60L
                )
        );

        service.checkAllowed(null);

        verify(keyFactory)
                .refreshIp("unknown");
    }

    private RefreshRateLimitProperties enabledProperties() {
        return new RefreshRateLimitProperties(
                true,
                3,
                WINDOW
        );
    }
}
