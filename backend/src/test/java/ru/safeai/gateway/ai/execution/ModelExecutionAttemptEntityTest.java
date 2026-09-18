package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelExecutionAttemptEntityTest {

    private static final Instant STARTED_AT =
            Instant.parse("2026-09-13T12:00:00Z");

    @Test
    void connectFailureIsKnownNotExecutedAndOnlySameTargetRetryable() {
        ModelExecutionAttemptEntity attempt = started();

        attempt.failed(
                new AiProviderUnavailableException(
                        "openai",
                        "gpt-5",
                        true,
                        false,
                        "connect failure",
                        null
                ),
                STARTED_AT.plusSeconds(1)
        );

        assertThat(attempt.getOutcome())
                .isEqualTo(ExecutionAttemptOutcome.FAILED);
        assertThat(attempt.getOutcomeCertainty())
                .isEqualTo(OutcomeCertainty.KNOWN_NOT_EXECUTED);
        assertThat(attempt.getRetrySafety())
                .isEqualTo(RetrySafety.SAME_TARGET_RETRY_ALLOWED);
        assertThat(attempt.getFallbackSafety())
                .isEqualTo(FallbackSafety.FALLBACK_FORBIDDEN);
    }

    @Test
    void providerRejectionIsKnownRejectedAndFallbackRemainsForbidden() {
        ModelExecutionAttemptEntity attempt = started();

        attempt.failed(
                new AiProviderException(
                        "openai",
                        "gpt-5",
                        400,
                        "provider-request-id",
                        AiProviderErrorType.INVALID_REQUEST,
                        false,
                        false,
                        null,
                        "invalid request",
                        null
                ),
                STARTED_AT.plusSeconds(1)
        );

        assertThat(attempt.getOutcome())
                .isEqualTo(ExecutionAttemptOutcome.FAILED);
        assertThat(attempt.getOutcomeCertainty())
                .isEqualTo(OutcomeCertainty.KNOWN_REJECTED);
        assertThat(attempt.getRetrySafety())
                .isEqualTo(RetrySafety.SAME_TARGET_RETRY_FORBIDDEN);
        assertThat(attempt.getFallbackSafety())
                .isEqualTo(FallbackSafety.FALLBACK_FORBIDDEN);
    }

    @Test
    void ambiguousTimeoutForbidsRetryAndFallback() {
        ModelExecutionAttemptEntity attempt = started();

        attempt.failed(
                new AiProviderTimeoutException(
                        "openai",
                        "gpt-5",
                        true,
                        "read timeout",
                        null
                ),
                STARTED_AT.plusSeconds(1)
        );

        assertThat(attempt.getOutcome())
                .isEqualTo(ExecutionAttemptOutcome.AMBIGUOUS);
        assertThat(attempt.getOutcomeCertainty())
                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
        assertThat(attempt.getRetrySafety())
                .isEqualTo(RetrySafety.SAME_TARGET_RETRY_FORBIDDEN);
        assertThat(attempt.getFallbackSafety())
                .isEqualTo(FallbackSafety.FALLBACK_FORBIDDEN);
    }

    private static ModelExecutionAttemptEntity started() {
        return ModelExecutionAttemptEntity.started(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                ProviderExecutionTarget.staticTarget(
                        "openai",
                        "gpt-5"
                ),
                STARTED_AT
        );
    }
}
