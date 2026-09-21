package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import java.util.Objects;

/** Conservative classification based on evidence, not a negated boolean. */
public final class ProviderFailureCertainty {
    private ProviderFailureCertainty() { }

    public static OutcomeCertainty classify(AiProviderException error) {
        Objects.requireNonNull(error, "error");
        if (error.isOutcomeAmbiguous()) {
            return OutcomeCertainty.AMBIGUOUS;
        }
        // A connect exception raised by a generic HTTP client does NOT prove
        // that zero request bytes reached the provider. The current adapters
        // have no typed pre-submission evidence, so network errors fail closed.
        if (error.getErrorType() == AiProviderErrorType.CONNECT_FAILURE) {
            return OutcomeCertainty.AMBIGUOUS;
        }
        Integer code = error.getStatusCode();
        if (code != null && code >= 400 && code < 500 && code != 408) {
            return OutcomeCertainty.KNOWN_REJECTED;
        }
        // 503/529/unknown failures cannot prove rejection merely because
        // one exception constructor set outcomeAmbiguous=false.
        return OutcomeCertainty.AMBIGUOUS;
    }
}
