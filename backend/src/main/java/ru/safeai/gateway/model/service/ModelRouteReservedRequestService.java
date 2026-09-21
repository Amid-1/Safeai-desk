package ru.safeai.gateway.model.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.chat.service.ChatContentNormalizer;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persisted, append-only exact pre-RAG request identity for a governed turn. */
@Service
public class ModelRouteReservedRequestService {
    private final JdbcTemplate jdbc;
    private final ModelRouteDecisionRepository decisions;
    private final ChatContentNormalizer normalizer;
    private final Clock clock;

    public ModelRouteReservedRequestService(
            JdbcTemplate jdbc,
            ModelRouteDecisionRepository decisions,
            ChatContentNormalizer normalizer,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Call after the ChatTurn is flushed, in the SAME reservation transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void seal(
            UUID decisionId,
            UUID chatTurnId,
            UUID clientRequestId,
            AiChatRequest baseRequest,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        Objects.requireNonNull(baseRequest, "baseRequest");
        Objects.requireNonNull(knowledgeMode, "knowledgeMode");
        ModelRouteDecision decision = readDecision(decisionId);
        if (decision.outcome() != ModelRouteOutcome.ALLOWED
                || decision.decisionIntegrityVersion() != 3
                || decision.selectedCatalogEntryId() == null
                || !Objects.equals(decision.chatTurnId(), chatTurnId)
                || !decision.clientRequestId().equals(clientRequestId)
                || !decision.organizationId().equals(baseRequest.organizationId())
                || !decision.userId().equals(baseRequest.userId())
                || !decision.chatId().equals(baseRequest.chatId())
                || !Objects.equals(decision.estimatedInputTokens(),
                        baseRequest.reservedInputTokens())
                || !Objects.equals(decision.estimatedOutputTokens(),
                        baseRequest.maxOutputTokens() == null
                                ? null : baseRequest.maxOutputTokens().longValue())
                || !AiInputUnitEstimator.VERSION.equals(decision.inputAccountingVersion())
                || !decision.requestContentHash().equals(normalizer.requestHash(
                        baseRequest.userMessage(), knowledgeBaseId, knowledgeMode))
                || decision.additionalInputUnitUpperBound() == null
                || Math.addExact(
                        AiInputUnitEstimator.estimateBaseRequest(
                                baseRequest.userMessage(), baseRequest.history()),
                        decision.additionalInputUnitUpperBound())
                        != decision.estimatedInputTokens()) {
            throw new IllegalStateException("Cannot seal a base request different from routed evidence");
        }
        int count = jdbc.update("""
                insert into public.model_route_reserved_requests (
                    model_route_decision_id, chat_turn_id, provider_operation_id,
                    base_request_sha256, created_at
                ) values (?, ?, ?, ?, ?)
                """, decisionId, chatTurnId, baseRequest.providerOperationId(),
                ModelRouteExecutionIdentity.baseSha256(baseRequest),
                Timestamp.from(clock.instant()));
        if (count != 1) {
            throw new IllegalStateException("Reserved request was not sealed");
        }
    }

    @Transactional(readOnly = true)
    public void requireSame(
            UUID decisionId,
            UUID chatTurnId,
            UUID providerOperationId,
            AiChatRequest baseRequest
    ) {
        Objects.requireNonNull(baseRequest, "baseRequest");
        List<String> hashes = jdbc.query("""
                select base_request_sha256
                from public.model_route_reserved_requests
                where model_route_decision_id = ?
                  and chat_turn_id = ?
                  and provider_operation_id = ?
                """, (rs, row) -> rs.getString(1),
                decisionId, chatTurnId, providerOperationId);
        if (hashes.size() != 1
                || !hashes.getFirst().equals(
                        ModelRouteExecutionIdentity.baseSha256(baseRequest))) {
            throw new IllegalStateException(
                    "Pre-RAG request differs from transactionally sealed route reservation");
        }
    }

    private ModelRouteDecision readDecision(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        ModelRouteDecision result = decisions.findById(decisionId)
                .orElseThrow(() -> new IllegalStateException("Route decision not found"));
        ModelRouteDecisionIntegrity.requireValid(result);
        return result;
    }
}
