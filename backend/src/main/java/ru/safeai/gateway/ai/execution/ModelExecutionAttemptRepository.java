package ru.safeai.gateway.ai.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ModelExecutionAttemptRepository
        extends JpaRepository<ModelExecutionAttemptEntity, UUID> {

    Optional<ModelExecutionAttemptEntity> findByProviderAttemptId(UUID providerAttemptId);

    /** Advisory preflight only. V53 DB trigger serializes and enforces INSERT. */
    Optional<ModelExecutionAttemptEntity> findTopByExecutionPlanIdOrderByAttemptNumberDesc(
            UUID executionPlanId);
}
