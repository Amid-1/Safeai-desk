package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.util.UUID;

/**
 * Authorization hook invoked by the governed physical-attempt scope.
 * Knowledge routes must obtain one durable permit for every physical attempt.
 */
public interface AiExecutionAttemptAuthorization {

    enum Outcome { SUCCEEDED, FAILED, AMBIGUOUS, ABORTED }

    UUID beforePhysicalAttempt(AiExecutionRequest request, AiProviderAttemptContext attempt);

    void afterPhysicalAttempt(
            AiExecutionRequest request,
            AiProviderAttemptContext attempt,
            UUID leaseId,
            Outcome outcome
    );

    static AiExecutionAttemptAuthorization noOp() {
        return new AiExecutionAttemptAuthorization() {
            @Override
            public UUID beforePhysicalAttempt(AiExecutionRequest request, AiProviderAttemptContext attempt) {
                if (request.knowledgeExecutionPermitId() != null
                        || request.executionIdentity().usesKnowledge()) {
                    throw new IllegalStateException(
                            "Knowledge physical execution requires a durable attempt-lease implementation");
                }
                return null;
            }

            @Override
            public void afterPhysicalAttempt(
                    AiExecutionRequest request,
                    AiProviderAttemptContext attempt,
                    UUID leaseId,
                    Outcome outcome
            ) {
                if (leaseId != null) {
                    throw new IllegalStateException("Unexpected Knowledge attempt lease");
                }
            }
        };
    }
}
