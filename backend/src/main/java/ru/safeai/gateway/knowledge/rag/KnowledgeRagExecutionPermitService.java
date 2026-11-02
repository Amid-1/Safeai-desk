package ru.safeai.gateway.knowledge.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.ai.execution.AiExecutionAttemptAuthorization;
import ru.safeai.gateway.ai.execution.AiExecutionRequest;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Durable Knowledge execution admission.
 *
 * <p>One permit binds a ChatTurn to exact source document versions. Each
 * physical provider attempt obtains a separate DB lease immediately before
 * the provider adapter reports STARTED. Deletion serializes with lease
 * acquisition through base/document row locks and waits for ACTIVE leases.</p>
 */
@Service
public class KnowledgeRagExecutionPermitService implements AiExecutionAttemptAuthorization {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRagExecutionPermitService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Duration leaseTtl;
    private final Duration permitTtl;
    private final ConcurrentMap<UUID, LeaseHandle> localLeases = new ConcurrentHashMap<>();

    public KnowledgeRagExecutionPermitService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${safeai.knowledge.execution-lease.ttl:PT10M}") Duration leaseTtl,
            @Value("${safeai.knowledge.execution-lease.permit-ttl:PT2H}") Duration permitTtl
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.leaseTtl = requirePositive(leaseTtl, "leaseTtl");
        this.permitTtl = requirePositive(permitTtl, "permitTtl");
        if (this.leaseTtl.compareTo(Duration.ofMinutes(2)) < 0) {
            throw new IllegalArgumentException("Knowledge execution lease TTL must be at least PT2M");
        }
    }

    public UUID createPermit(
            UUID chatTurnId,
            UUID organizationId,
            UUID knowledgeBaseId,
            UUID retrievalRunId,
            String contextSha256,
            List<KnowledgeContextSource> sources
    ) {
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        Objects.requireNonNull(retrievalRunId, "retrievalRunId");
        Objects.requireNonNull(contextSha256, "contextSha256");
        List<SourceVersion> exactSources = sourceVersions(sources);
        UUID permitId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            lockAvailableBase(organizationId, knowledgeBaseId);
            lockAvailableSources(organizationId, knowledgeBaseId, exactSources);
            int inserted = jdbc.update("""
                    INSERT INTO public.knowledge_rag_execution_permits_v85
                        (id, organization_id, knowledge_base_id, chat_turn_id,
                         retrieval_run_id, context_sha256, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, clock_timestamp() + (? * interval '1 second'))
                    """, permitId, organizationId, knowledgeBaseId, chatTurnId,
                    retrievalRunId, contextSha256, permitTtl.toSeconds());
            if (inserted != 1) {
                throw new IllegalStateException("KNOWLEDGE_EXECUTION_PERMIT_NOT_CREATED");
            }
            for (SourceVersion source : exactSources) {
                jdbc.update("""
                        INSERT INTO public.knowledge_rag_execution_permit_sources_v85
                            (permit_id, organization_id, knowledge_base_id,
                             document_id, document_version_id)
                        VALUES (?, ?, ?, ?, ?)
                        """, permitId, organizationId, knowledgeBaseId,
                        source.documentId(), source.versionId());
            }
        });
        return permitId;
    }

    /** Must join the ChatTurn success transaction. Lock order: base, documents, permit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertFinalizationAllowed(UUID permitId, UUID chatTurnId, UUID organizationId) {
        Permit permit = findPermit(permitId);
        if (!permit.chatTurnId().equals(chatTurnId) || !permit.organizationId().equals(organizationId)) {
            throw new KnowledgeRagContextRevokedException();
        }
        lockAvailableBase(permit.organizationId(), permit.knowledgeBaseId());
        List<SourceVersion> sources = loadSources(permitId);
        lockAvailableSources(permit.organizationId(), permit.knowledgeBaseId(), sources);

        List<String> active = jdbc.queryForList("""
                SELECT state
                  FROM public.knowledge_rag_execution_permits_v85
                 WHERE id=? AND organization_id=? AND chat_turn_id=?
                   AND state='ACTIVE' AND expires_at > clock_timestamp()
                 FOR UPDATE
                """, String.class, permitId, organizationId, chatTurnId);
        if (active.size() != 1) throw new KnowledgeRagContextRevokedException();

        int completed = jdbc.update("""
                UPDATE public.knowledge_rag_execution_permits_v85
                   SET state='COMPLETED', completed_at=clock_timestamp()
                 WHERE id=? AND organization_id=? AND chat_turn_id=?
                   AND state='ACTIVE' AND expires_at > clock_timestamp()
                """, permitId, organizationId, chatTurnId);
        if (completed != 1) throw new KnowledgeRagContextRevokedException();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokePermitAfterRejectedFinalization(UUID permitId) {
        if (permitId == null) return;
        jdbc.update("""
                UPDATE public.knowledge_rag_execution_permits_v85
                   SET state='REVOKED', completed_at=clock_timestamp()
                 WHERE id=? AND state='ACTIVE'
                """, permitId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closePermit(UUID permitId) {
        if (permitId == null) return;
        jdbc.update("""
                UPDATE public.knowledge_rag_execution_permits_v85
                   SET state='CLOSED', completed_at=clock_timestamp()
                 WHERE id=? AND state='ACTIVE'
                """, permitId);
    }

    @Override
    public UUID beforePhysicalAttempt(AiExecutionRequest request, AiProviderAttemptContext attempt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(attempt, "attempt");
        UUID permitId = request.knowledgeExecutionPermitId();
        if (!request.executionIdentity().usesKnowledge()) {
            if (permitId != null) throw new IllegalStateException("GENERAL execution cannot use a Knowledge permit");
            return null;
        }
        if (permitId == null || !request.aiRequest().providerOperationId().equals(attempt.operationId())) {
            throw new KnowledgeRagContextRevokedException();
        }

        UUID leaseId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID organizationId = request.executionIdentity().organizationId();
        if (organizationId == null) throw new KnowledgeRagContextRevokedException();

        transactions.executeWithoutResult(status -> {
            Permit permit = findPermit(permitId);
            if (!permit.chatTurnId().equals(request.chatTurnId())
                    || !permit.organizationId().equals(organizationId)
                    || !permit.knowledgeBaseId().equals(request.executionIdentity().knowledgeBaseId())) {
                throw new KnowledgeRagContextRevokedException();
            }
            lockAvailableBase(organizationId, permit.knowledgeBaseId());
            lockAvailableSources(organizationId, permit.knowledgeBaseId(), loadSources(permitId));
            List<String> active = jdbc.queryForList("""
                    SELECT state
                      FROM public.knowledge_rag_execution_permits_v85
                     WHERE id=? AND organization_id=? AND chat_turn_id=?
                       AND state='ACTIVE' AND expires_at > clock_timestamp()
                     FOR UPDATE
                    """, String.class, permitId, organizationId, request.chatTurnId());
            if (active.size() != 1) throw new KnowledgeRagContextRevokedException();

            int inserted = jdbc.update("""
                    INSERT INTO public.knowledge_rag_execution_attempt_leases_v85
                        (id, permit_id, provider_attempt_id, attempt_number, lease_token, lease_until)
                    VALUES (?, ?, ?, ?, ?, clock_timestamp() + (? * interval '1 second'))
                    """, leaseId, permitId, attempt.attemptId(), attempt.attemptNumber(),
                    token, leaseTtl.toSeconds());
            if (inserted != 1) throw new KnowledgeRagContextRevokedException();
        });

        LeaseHandle previous = localLeases.putIfAbsent(attempt.attemptId(), new LeaseHandle(leaseId, token));
        if (previous != null) throw new IllegalStateException("Duplicate local Knowledge attempt lease");
        return leaseId;
    }

    @Override
    public void afterPhysicalAttempt(
            AiExecutionRequest request,
            AiProviderAttemptContext attempt,
            UUID leaseId,
            Outcome outcome
    ) {
        if (leaseId == null) {
            if (request.executionIdentity().usesKnowledge()) throw new IllegalStateException("Knowledge attempt lease is missing");
            return;
        }
        LeaseHandle handle = localLeases.get(attempt.attemptId());
        if (handle == null || !handle.id().equals(leaseId)) {
            throw new IllegalStateException("Knowledge attempt lease ownership is unavailable");
        }
        int updated = jdbc.update("""
                UPDATE public.knowledge_rag_execution_attempt_leases_v85
                   SET state=?, completed_at=clock_timestamp(), error_code=?
                 WHERE id=? AND lease_token=? AND state='ACTIVE'
                """, outcome.name(), outcome == Outcome.AMBIGUOUS ? "PROVIDER_OUTCOME_AMBIGUOUS" : null,
                handle.id(), handle.token());
        if (updated != 1) throw new IllegalStateException("Knowledge attempt lease was fenced before completion");
        localLeases.remove(attempt.attemptId(), handle);
    }

    /** Heartbeat only leases owned by this JVM; other replicas cannot renew them. */
    @Scheduled(fixedDelayString = "${safeai.knowledge.execution-lease.heartbeat-delay:PT15S}")
    public void heartbeatLocalLeases() {
        for (var entry : localLeases.entrySet()) {
            LeaseHandle handle = entry.getValue();
            try {
                int updated = jdbc.update("""
                        UPDATE public.knowledge_rag_execution_attempt_leases_v85
                           SET lease_until=clock_timestamp() + (? * interval '1 second'),
                               heartbeat_at=clock_timestamp()
                         WHERE id=? AND lease_token=? AND state='ACTIVE'
                        """, leaseTtl.toSeconds(), handle.id(), handle.token());
                if (updated != 1) log.error("Knowledge execution heartbeat lost ownership: attemptId={}", entry.getKey());
            } catch (RuntimeException failure) {
                // Do not release on heartbeat failure. ACTIVE DB evidence blocks erasure.
                log.error("Knowledge execution heartbeat failed: attemptId={}", entry.getKey(), failure);
            }
        }
    }

    /** Expired leases are reconciled, never silently dropped. */
    @Scheduled(fixedDelayString = "${safeai.knowledge.execution-lease.recovery-delay:PT30S}")
    public void recoverExpiredLeases() {
        List<ExpiredLease> expired = jdbc.query("""
                SELECT l.id, l.provider_attempt_id, a.outcome
                  FROM public.knowledge_rag_execution_attempt_leases_v85 l
                  LEFT JOIN public.model_execution_attempts a ON a.provider_attempt_id=l.provider_attempt_id
                 WHERE l.state='ACTIVE' AND l.lease_until <= clock_timestamp()
                 ORDER BY l.lease_until, l.id LIMIT 100
                """, (rs, n) -> new ExpiredLease(rs.getObject("id", UUID.class),
                rs.getObject("provider_attempt_id", UUID.class), rs.getString("outcome")));
        for (ExpiredLease item : expired) {
            // An expired clock lease does NOT prove provider completion. The
            // external HTTP request can still be in flight after a JVM crash,
            // STW pause or network partition. Never unblock physical erasure
            // based on age alone. Only independently recorded terminal
            // execution evidence can close an attempt lease.
            String terminal = switch (item.outcome() == null ? "MISSING" : item.outcome()) {
                case "SUCCEEDED" -> "SUCCEEDED";
                case "FAILED" -> "FAILED";
                case "PROTOCOL_REJECTED" -> "FAILED";
                default -> null;
            };
            if (terminal == null) {
                log.warn("Knowledge attempt lease expired with unresolved provider outcome; "
                        + "erasure remains blocked: attemptId={}", item.providerAttemptId());
                continue;
            }
            int updated = jdbc.update("""
                    UPDATE public.knowledge_rag_execution_attempt_leases_v85
                       SET state=?, completed_at=clock_timestamp()
                     WHERE id=? AND state='ACTIVE' AND lease_until <= clock_timestamp()
                    """, terminal, item.id());
            if (updated == 1) {
                localLeases.entrySet().removeIf(e -> e.getValue().id().equals(item.id()));
            }
        }
    }

    public boolean hasActiveAttemptsForDocument(UUID organizationId, UUID baseId, UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.knowledge_rag_execution_attempt_leases_v85 l
                    JOIN public.knowledge_rag_execution_permits_v85 p ON p.id=l.permit_id
                    JOIN public.knowledge_rag_execution_permit_sources_v85 s ON s.permit_id=p.id
                    WHERE s.organization_id=? AND s.knowledge_base_id=?
                      AND s.document_id=? AND l.state='ACTIVE'
                )
                """, Boolean.class, organizationId, baseId, documentId));
    }

    public boolean hasActiveAttemptsForBase(UUID organizationId, UUID baseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.knowledge_rag_execution_attempt_leases_v85 l
                    JOIN public.knowledge_rag_execution_permits_v85 p ON p.id=l.permit_id
                    WHERE p.organization_id=? AND p.knowledge_base_id=? AND l.state='ACTIVE'
                )
                """, Boolean.class, organizationId, baseId));
    }

    private Permit findPermit(UUID permitId) {
        List<Permit> permits = jdbc.query("""
                SELECT organization_id, knowledge_base_id, chat_turn_id
                  FROM public.knowledge_rag_execution_permits_v85 WHERE id=?
                """, (rs, n) -> new Permit(rs.getObject("organization_id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class), rs.getObject("chat_turn_id", UUID.class)), permitId);
        if (permits.size() != 1) throw new KnowledgeRagContextRevokedException();
        return permits.getFirst();
    }

    private List<SourceVersion> loadSources(UUID permitId) {
        return jdbc.query("""
                SELECT document_id, document_version_id
                  FROM public.knowledge_rag_execution_permit_sources_v85
                 WHERE permit_id=? ORDER BY document_id, document_version_id
                """, (rs, n) -> new SourceVersion(rs.getObject("document_id", UUID.class),
                rs.getObject("document_version_id", UUID.class)), permitId);
    }

    private void lockAvailableBase(UUID organizationId, UUID baseId) {
        List<String> states = jdbc.queryForList("""
                SELECT lifecycle_state FROM public.knowledge_bases
                 WHERE id=? AND organization_id=? AND enabled AND lifecycle_state='AVAILABLE'
                 FOR SHARE
                """, String.class, baseId, organizationId);
        if (states.size() != 1) throw new KnowledgeRagContextRevokedException();
    }

    private void lockAvailableSources(UUID organizationId, UUID baseId, List<SourceVersion> sources) {
        for (SourceVersion source : sources) {
            List<UUID> ids = jdbc.queryForList("""
                    SELECT d.id FROM public.knowledge_documents d
                    JOIN public.knowledge_document_versions v
                      ON v.document_id=d.id AND v.organization_id=d.organization_id
                     AND v.knowledge_base_id=d.knowledge_base_id
                    WHERE d.id=? AND d.organization_id=? AND d.knowledge_base_id=?
                      AND d.enabled AND d.lifecycle_state='AVAILABLE' AND v.id=?
                    FOR SHARE OF d, v
                    """, UUID.class, source.documentId(), organizationId, baseId, source.versionId());
            if (ids.size() != 1 || !source.documentId().equals(ids.getFirst())) {
                throw new KnowledgeRagContextRevokedException();
            }
        }
    }

    private static List<SourceVersion> sourceVersions(List<KnowledgeContextSource> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        return sources.stream().map(source -> {
            if (source == null || source.hit() == null || source.hit().documentId() == null
                    || source.hit().documentVersionId() == null) throw new KnowledgeRagContextRevokedException();
            return new SourceVersion(source.hit().documentId(), source.hit().documentVersionId());
        }).distinct().sorted(Comparator.comparing(SourceVersion::documentId)
                .thenComparing(SourceVersion::versionId)).toList();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private record SourceVersion(UUID documentId, UUID versionId) { }
    private record Permit(UUID organizationId, UUID knowledgeBaseId, UUID chatTurnId) { }
    private record LeaseHandle(UUID id, UUID token) { }
    private record ExpiredLease(UUID id, UUID providerAttemptId, String outcome) { }
}
