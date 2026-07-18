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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginRateLimitServiceTest {

    private static final String EMAIL = "admin@test.com";
    private static final String IP = "127.0.0.1";

    private static final String EMAIL_KEY =
            "safeai:test:rate-limit:login:email";

    private static final String IP_KEY =
            "safeai:test:rate-limit:login:ip";

    private static final Duration WINDOW =
            Duration.ofMinutes(10);

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
                        WINDOW
                ),
                keyFactory
        );
    }

    @Test
    void withinLimitsUsesOneAtomicDualIncrement() {
        stubKeys();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                10,
                IP_KEY,
                30,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.ALLOWED,
                1,
                1,
                600,
                600,
                false
        ));

        assertThatCode(() ->
                service.checkAllowed(
                        " Admin@Test.COM ",
                        IP
                )
        ).doesNotThrowAnyException();

        verify(rateLimiter).incrementBothAndCheck(
                EMAIL_KEY,
                10,
                IP_KEY,
                30,
                WINDOW
        );
    }

    @Test
    void emailLimitExceededStillUpdatesBothDimensionsAtomically() {
        stubKeys();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                10,
                IP_KEY,
                30,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                11,
                5,
                600,
                500,
                false
        ));

        assertThatThrownBy(() ->
                service.checkAllowed(EMAIL, IP)
        )
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining(
                        "Слишком много попыток входа"
                );

        verify(rateLimiter).incrementBothAndCheck(
                EMAIL_KEY,
                10,
                IP_KEY,
                30,
                WINDOW
        );
    }

    @Test
    void ipLimitExceededUsesSamePublicMessage() {
        stubKeys();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                10,
                IP_KEY,
                30,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.SECOND_EXCEEDED,
                3,
                31,
                400,
                600,
                false
        ));

        assertThatThrownBy(() ->
                service.checkAllowed(EMAIL, IP)
        )
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining(
                        "Слишком много попыток входа"
                )
                .hasMessageNotContaining("email")
                .hasMessageNotContaining("IP");
    }

    @Test
    void bothLimitsExceededThrowsRateLimitExceeded() {
        stubKeys();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                10,
                IP_KEY,
                30,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.BOTH_EXCEEDED,
                11,
                31,
                400,
                600,
                false
        ));

        assertThatThrownBy(() ->
                service.checkAllowed(EMAIL, IP)
        ).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void redisFailureReturnsRateLimitUnavailable() {
        stubKeys();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                10,
                IP_KEY,
                30,
                WINDOW
        )).thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() ->
                service.checkAllowed(EMAIL, IP)
        )
                .isInstanceOf(RateLimitUnavailableException.class)
                .hasMessageContaining(
                        "Redis login rate limit недоступен"
                );
    }

    @Test
    void resetEmailLimitUsesNormalizedEmailKey() {
        when(keyFactory.loginEmail(EMAIL))
                .thenReturn(EMAIL_KEY);

        service.resetEmailLimit(" Admin@Test.COM ");

        verify(keyFactory).loginEmail(EMAIL);
        verify(rateLimiter).reset(EMAIL_KEY);
    }

    @Test
    void resetEmailFailureIsBestEffort() {
        when(keyFactory.loginEmail(EMAIL))
                .thenReturn(EMAIL_KEY);

        org.mockito.Mockito.doThrow(
                new RuntimeException("Redis unavailable")
        ).when(rateLimiter).reset(EMAIL_KEY);

        assertThatCode(() ->
                service.resetEmailLimit(EMAIL)
        ).doesNotThrowAnyException();
    }

    @Test
    void disabledLimiterDoesNotResolveKeys() {
        LoginRateLimitService disabledService =
                new LoginRateLimitService(
                        rateLimiter,
                        new LoginRateLimitProperties(
                                false,
                                10,
                                30,
                                WINDOW
                        ),
                        keyFactory
                );

        disabledService.checkAllowed(EMAIL, IP);

        verifyNoInteractions(rateLimiter, keyFactory);
    }

    @Test
    void blankEmailAndIpUseUnknownIdentities() {
        when(keyFactory.loginEmail("unknown"))
                .thenReturn(EMAIL_KEY);

        when(keyFactory.loginIp("unknown"))
                .thenReturn(IP_KEY);

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                10,
                IP_KEY,
                30,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.ALLOWED,
                1,
                1,
                600,
                600,
                false
        ));

        service.checkAllowed(" ", null);

        verify(keyFactory).loginEmail("unknown");
        verify(keyFactory).loginIp("unknown");
    }

    private void stubKeys() {
        when(keyFactory.loginEmail(EMAIL))
                .thenReturn(EMAIL_KEY);

        when(keyFactory.loginIp(IP))
                .thenReturn(IP_KEY);
    }
}
