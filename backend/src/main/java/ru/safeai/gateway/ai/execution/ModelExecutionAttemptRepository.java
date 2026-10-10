package ru.safeai.gateway.ai.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ModelExecutionAttemptRepository
        extends JpaRepository<ModelExecutionAttemptEntity, UUID> {

    Optional<ModelExecutionAttemptEntity> findByProviderAttemptId(UUID providerAttemptId);

    /**
     * Friendly, non-locking preflight. V72 DB trigger is the authoritative
     * concurrent fence, holding the organization row through STARTED commit.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                  FROM public.model_execution_plans p
                  JOIN public.chat_turns t ON t.id = p.chat_turn_id
                  JOIN public.organizations o ON o.id = p.organization_id
                 WHERE p.id = :planId
                   AND p.organization_id = t.organization_id
                   AND o.enabled = true
                   AND t.authorized_organization_auth_version IS NOT NULL
                   AND t.authorized_organization_auth_version = o.auth_version
            )
            """, nativeQuery = true)
    boolean organizationEpochCurrent(@Param("planId") UUID planId);

    /** Advisory preflight only. V53 DB trigger serializes and enforces INSERT. */
    Optional<ModelExecutionAttemptEntity> findTopByExecutionPlanIdOrderByAttemptNumberDesc(
            UUID executionPlanId);
}
