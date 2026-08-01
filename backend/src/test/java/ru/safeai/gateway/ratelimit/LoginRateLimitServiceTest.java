package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;
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
class LoginRateLimitServiceTest {

    private static final UUID AUDIT_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final String EMAIL =
            "admin@test.com";

    private static final String IP =
            "127.0.0.1";

    private static final String EMAIL_KEY =
            "safeai:test:v1:rate-limit:{login}:email:hash";

    private static final String IP_KEY =
            "safeai:test:v1:rate-limit:{login}:ip:hash";

    private static final String EMAIL_MARKER =
            EMAIL_KEY + ":exceeded";

    private static final String IP_MARKER =
            IP_KEY + ":exceeded";

    private static final Duration WINDOW =
            Duration.ofMinutes(10);

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    @Mock
    private RateLimitKeyFactory keyFactory;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private RateLimitMetrics metrics;

    @Mock
    private Timer.Sample timerSample;

    private LoginRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new LoginRateLimitService(
                rateLimiter,
                enabledProperties(),
                keyFactory,
                eventPublisher,
                metrics
        );
    }

    @Test
    void withinLimitsUsesOneAtomicDualIncrement() {
        stubTimer();
        stubKeys();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                EMAIL_MARKER,
                10,
                IP_KEY,
                IP_MARKER,
                30,
                WINDOW
        )).thenReturn(allowedResult());

        assertThatCode(() ->
                service.checkAllowed(
                        " Admin@Test.COM ",
                        IP
                )
        ).doesNotThrowAnyException();

        verify(rateLimiter).incrementBothAndCheck(
                EMAIL_KEY,
                EMAIL_MARKER,
                10,
                IP_KEY,
                IP_MARKER,
                30,
                WINDOW
        );

        verify(metrics)
                .recordAllowed("login");

        verify(metrics).finishRedisOperation(
                timerSample,
                "login",
                "check",
                "success"
        );

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void firstEmailExceedPublishesOneEmailEvent() {
        stubKeys();
        stubFingerprints();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                EMAIL_MARKER,
                10,
                IP_KEY,
                IP_MARKER,
                30,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                11,
                5,
                600,
                500,
                true,
                false
        ));

        RateLimitExceededException exception =
                org.assertj.core.api.Assertions
                        .catchThrowableOfType(
                                RateLimitExceededException.class,
                                () -> service.checkAllowed(
                                        EMAIL,
                                        IP
                                )
                        );

        assertThat(exception)
                .isNotNull();

        assertThat(exception.getRetryAfterSeconds())
                .isEqualTo(600);

        ArgumentCaptor<RateLimitExceededEvent> captor =
                ArgumentCaptor.forClass(
                        RateLimitExceededEvent.class
                );

        verify(eventPublisher)
                .publishEvent(captor.capture());

        RateLimitExceededEvent event =
                captor.getValue();

        assertThat(event.actorUserId())
                .isNull();

        assertThat(event.targetOrganizationId())
                .isEqualTo(AUDIT_ORGANIZATION_ID);

        assertThat(event.type())
                .isEqualTo("LOGIN_EMAIL");

        assertThat(event.limit())
                .isEqualTo(10);

        assertThat(event.details())
                .containsEntry(
                        "dimension",
                        "EMAIL"
                )
                .containsEntry(
                        "emailCount",
                        11L
                )
                .containsEntry(
                        "ipCount",
                        5L
                )
                .containsEntry(
                        "emailNotification",
                        true
                )
                .containsEntry(
                        "ipNotification",
                        false
                );

        verify(metrics).recordRejected(
                "login",
                "email"
        );
    }

    @Test
    void repeatedEmailExceedDoesNotPublishAuditAgain() {
        stubKeys();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                EMAIL_MARKER,
                10,
                IP_KEY,
                IP_MARKER,
                30,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.FIRST_EXCEEDED,
                12,
                6,
                590,
                490,
                false,
                false
        ));

        assertThatThrownBy(() ->
                service.checkAllowed(
                        EMAIL,
                        IP
                )
        ).isInstanceOf(
                RateLimitExceededException.class
        );

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void bothNotificationsProduceEventWithTwoLimitsAndNullSingleLimit() {
        stubKeys();
        stubFingerprints();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                EMAIL_MARKER,
                10,
                IP_KEY,
                IP_MARKER,
                30,
                WINDOW
        )).thenReturn(new DualRateLimitResult(
                RateLimitDecision.BOTH_EXCEEDED,
                11,
                31,
                400,
                600,
                true,
                true
        ));

        assertThatThrownBy(() ->
                service.checkAllowed(
                        EMAIL,
                        IP
                )
        ).isInstanceOf(
                RateLimitExceededException.class
        );

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
                        "LOGIN_EMAIL_AND_IP"
                );

        assertThat(event.limit())
                .isNull();

        assertThat(event.details())
                .containsEntry(
                        "emailLimit",
                        10
                )
                .containsEntry(
                        "ipLimit",
                        30
                )
                .containsEntry(
                        "dimension",
                        "BOTH"
                );
    }

    @Test
    void eventPublicationFailureReleasesOnlyCreatedMarkersAndKeeps429() {
        stubKeys();
        stubFingerprints();

        DualRateLimitResult result =
                new DualRateLimitResult(
                        RateLimitDecision.FIRST_EXCEEDED,
                        11,
                        4,
                        600,
                        500,
                        true,
                        false
                );

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                EMAIL_MARKER,
                10,
                IP_KEY,
                IP_MARKER,
                30,
                WINDOW
        )).thenReturn(result);

        doThrow(
                new RuntimeException(
                        "audit unavailable"
                )
        )
                .when(eventPublisher)
                .publishEvent(any());

        assertThatThrownBy(() ->
                service.checkAllowed(
                        EMAIL,
                        IP
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
                        EMAIL_MARKER,
                        IP_MARKER
                );

        verify(metrics)
                .recordAuditPublishFailed(
                        "login"
                );
    }

    @Test
    void redisFailureReturnsRateLimitUnavailableAndRecordsMetrics() {
        stubTimer();
        stubKeys();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                EMAIL_MARKER,
                10,
                IP_KEY,
                IP_MARKER,
                30,
                WINDOW
        )).thenThrow(
                new RuntimeException(
                        "Redis unavailable"
                )
        );

        assertThatThrownBy(() ->
                service.checkAllowed(
                        EMAIL,
                        IP
                )
        )
                .isInstanceOf(
                        RateLimitUnavailableException.class
                )
                .hasMessageContaining(
                        "Redis login rate limit недоступен"
                );

        verify(metrics)
                .recordUnavailable(
                        "login"
                );

        verify(metrics).finishRedisOperation(
                timerSample,
                "login",
                "check",
                "error"
        );

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void successfulLoginClearsEmailAndDecrementsIpExactlyOnce() {
        stubTimer();
        stubKeys();

        service.onLoginSuccess(
                " Admin@Test.COM ",
                IP
        );

        verify(rateLimiter)
                .resetFirstAndDecrementSecond(
                        EMAIL_KEY,
                        IP_KEY,
                        EMAIL_MARKER,
                        IP_MARKER
                );

        verify(metrics).finishRedisOperation(
                timerSample,
                "login",
                "login_success_cleanup",
                "success"
        );
    }

    @Test
    void successfulLoginCleanupIsBestEffort() {
        stubKeys();

        doThrow(
                new RuntimeException(
                        "Redis unavailable"
                )
        )
                .when(rateLimiter)
                .resetFirstAndDecrementSecond(
                        EMAIL_KEY,
                        IP_KEY,
                        EMAIL_MARKER,
                        IP_MARKER
                );

        assertThatCode(() ->
                service.onLoginSuccess(
                        EMAIL,
                        IP
                )
        ).doesNotThrowAnyException();

        verify(metrics)
                .recordUnavailable(
                        "login"
                );
    }

    @Test
    void blankIdentitiesUseOneExplicitReservedBucket() {
        when(keyFactory.loginEmail("unknown"))
                .thenReturn(EMAIL_KEY);

        when(keyFactory.loginIp("unknown"))
                .thenReturn(IP_KEY);

        stubMarkers();

        when(rateLimiter.incrementBothAndCheck(
                EMAIL_KEY,
                EMAIL_MARKER,
                10,
                IP_KEY,
                IP_MARKER,
                30,
                WINDOW
        )).thenReturn(allowedResult());

        service.checkAllowed(
                " ",
                null
        );

        verify(keyFactory)
                .loginEmail("unknown");

        verify(keyFactory)
                .loginIp("unknown");
    }

    @Test
    void disabledLimiterDoesNotResolveKeysOrTouchRedis() {
        LoginRateLimitService disabledService =
                new LoginRateLimitService(
                        rateLimiter,
                        new LoginRateLimitProperties(
                                false,
                                10,
                                30,
                                WINDOW,
                                null
                        ),
                        keyFactory,
                        eventPublisher,
                        metrics
                );

        disabledService.checkAllowed(
                EMAIL,
                IP
        );

        disabledService.onLoginSuccess(
                EMAIL,
                IP
        );

        verifyNoInteractions(
                rateLimiter,
                keyFactory,
                eventPublisher
        );

        verify(metrics, never())
                .startRedisOperation();
    }

    private void stubTimer() {
        when(metrics.startRedisOperation())
                .thenReturn(timerSample);
    }

    private LoginRateLimitProperties enabledProperties() {
        return new LoginRateLimitProperties(
                true,
                10,
                30,
                WINDOW,
                AUDIT_ORGANIZATION_ID
        );
    }

    private DualRateLimitResult allowedResult() {
        return new DualRateLimitResult(
                RateLimitDecision.ALLOWED,
                1,
                1,
                600,
                600,
                false,
                false
        );
    }

    private void stubKeys() {
        when(keyFactory.loginEmail(EMAIL))
                .thenReturn(EMAIL_KEY);

        when(keyFactory.loginIp(IP))
                .thenReturn(IP_KEY);

        stubMarkers();
    }

    private void stubMarkers() {
        when(keyFactory.exceededMarker(EMAIL_KEY))
                .thenReturn(EMAIL_MARKER);

        when(keyFactory.exceededMarker(IP_KEY))
                .thenReturn(IP_MARKER);
    }

    private void stubFingerprints() {
        when(keyFactory.emailFingerprint(EMAIL))
                .thenReturn(
                        "email-fingerprint"
                );

        when(keyFactory.ipFingerprint(IP))
                .thenReturn(
                        "ip-fingerprint"
                );
    }
}