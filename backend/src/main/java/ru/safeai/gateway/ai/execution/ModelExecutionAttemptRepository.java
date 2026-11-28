package ru.safeai.gateway.ai.execution;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /** V100 consumes a durable one-shot permit and retains V96 quota validation. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT public.consume_model_dispatch_v100(:planId, :attemptId)", nativeQuery = true)
    boolean consumeDispatchAuthorization(@Param("planId") UUID planId, @Param("attemptId") UUID attemptId);

    /** tenant -> attempt row; never wait for a tenant lock while holding the attempt row. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT public.lock_model_settlement_v100(:attemptId)", nativeQuery = true)
    boolean lockSettlement(@Param("attemptId") UUID attemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ModelExecutionAttemptEntity a where a.providerAttemptId = :attemptId")
    Optional<ModelExecutionAttemptEntity> findForSettlement(@Param("attemptId") UUID attemptId);

    /** Advisory preflight only. V53 DB trigger serializes and enforces INSERT. */
    Optional<ModelExecutionAttemptEntity> findTopByExecutionPlanIdOrderByAttemptNumberDesc(
            UUID executionPlanId);
}
