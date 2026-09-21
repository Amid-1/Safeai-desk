package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderFailureCertaintyTest {
    @Test
    void unprovenTransportFailureIsAmbiguousEvenIfLegacyFlagClaimsOtherwise() {
        var error = new AiProviderUnavailableException(
                "openai", "gpt-5", true, false, "network failure", null);
        assertThat(ProviderFailureCertainty.classify(error))
                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
    }

    @Test
    void knownHttpRejectionIsNotExecution() {
        var error = new AiProviderException(
                "openai", "gpt-5", 429, "provider-id",
                AiProviderErrorType.RATE_LIMITED, true, false,
                null, "rate limited", null);
        assertThat(ProviderFailureCertainty.classify(error))
                .isEqualTo(OutcomeCertainty.KNOWN_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429})
    void explicitNonAmbiguousHttpFourxxRejectionHasRejectionEvidence(int httpStatus) {
        AiProviderException response = new AiProviderException(
                "openai", "gpt-5", httpStatus, "request-id",
                AiProviderErrorType.INVALID_REQUEST, true, false,
                null, "provider rejected request", null);
        assertThat(ProviderFailureCertainty.classify(response))
                .isEqualTo(OutcomeCertainty.KNOWN_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 503, 529})
    void timeoutAndServerHttpStatusesRemainAmbiguousDespiteLegacyFlag(int httpStatus) {
        AiProviderException response = new AiProviderException(
                "openai", "gpt-5", httpStatus, null,
                AiProviderErrorType.SERVER_ERROR, true, false,
                null, "unproved execution stage", null);
        assertThat(ProviderFailureCertainty.classify(response))
                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
    }

    @Test
    void explicitAmbiguityOverridesOtherwiseKnownFourxxRejection() {
        AiProviderException response = new AiProviderException(
                "openai", "gpt-5", 429, null,
                AiProviderErrorType.RATE_LIMITED, true, true,
                null, "ambiguous 429", null);
        assertThat(ProviderFailureCertainty.classify(response))
                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
    }

    @Test
    void unprovedServerErrorIsAmbiguousRegardlessOfBooleanFlag() {
        var error = new AiProviderException(
                "openai", "gpt-5", 503, null,
                AiProviderErrorType.OVERLOADED, true, false,
                null, "overloaded", null);
        assertThat(ProviderFailureCertainty.classify(error))
                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
    }
}
