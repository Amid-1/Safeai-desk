package ru.safeai.gateway.knowledge.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.knowledge.dto.AnswerCitationResponse;
import ru.safeai.gateway.knowledge.dto.AnswerPassportResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class AnswerPassportService {

    private final JdbcTemplate jdbc;
    private final AuditEventService audit;

    public AnswerPassportService(
            JdbcTemplate jdbc,
            AuditEventService audit
    ) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public AnswerPassportResponse persist(
            ChatProcessingContext context,
            UUID assistantMessageId,
            String provider,
            RagCompletion completion,
            SafeAiUserPrincipal user,
            Instant now
    ) {
        if (!completion.preparation().usesKnowledge()) {
            return null;
        }

        RagPreparation preparation = completion.preparation();
        AiChatResponse response = completion.response();
        UUID passportId = UUID.randomUUID();

        jdbc.update("""
                insert into knowledge_answer_passports (
                    id, organization_id, knowledge_base_id, user_id,
                    chat_id, chat_turn_id, retrieval_run_id,
                    assistant_message_id, knowledge_mode, provider,
                    requested_model, resolved_model, embedding_model,
                    context_sha256, answer_sha256, evidence_sufficient,
                    citations_valid, citation_count, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                passportId,
                user.getOrganizationId(),
                preparation.knowledgeBaseId(),
                user.getId(),
                context.chatId(),
                context.turnId(),
                preparation.retrievalRunId(),
                assistantMessageId,
                preparation.mode().name(),
                provider,
                response.requestedModel(),
                response.model(),
                preparation.embeddingModel(),
                preparation.contextSha256(),
                KnowledgeHashing.sha256(response.content()),
                completion.evidenceSufficient(),
                completion.citationsValid(),
                completion.citations().size(),
                Timestamp.from(now)
        );

        Map<String, KnowledgeContextSource> sources = preparation.sources()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        KnowledgeContextSource::label,
                        source -> source
                ));
        List<CitationRow> citationRows = completion.citations().stream()
                .sorted(Comparator.comparingInt(RagCitation::ordinal))
                .map(citation -> new CitationRow(
                        citation,
                        sources.get(citation.label())
                ))
                .toList();
        jdbc.batchUpdate("""
                insert into knowledge_answer_citations (
                    id, answer_passport_id, retrieval_run_id,
                    organization_id, knowledge_base_id, chunk_id,
                    citation_label, ordinal, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                citationRows,
                100,
                (statement, row) -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, passportId);
                    statement.setObject(3, preparation.retrievalRunId());
                    statement.setObject(4, user.getOrganizationId());
                    statement.setObject(5, preparation.knowledgeBaseId());
                    statement.setObject(6, row.citation().chunkId());
                    statement.setString(7, row.citation().label());
                    statement.setInt(8, row.citation().ordinal());
                    statement.setTimestamp(9, Timestamp.from(now));
                }
        );

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_ANSWER_GENERATED,
                Map.of(
                        "chatTurnId", context.turnId().toString(),
                        "retrievalRunId", preparation.retrievalRunId().toString(),
                        "answerPassportId", passportId.toString(),
                        "knowledgeMode", preparation.mode().name(),
                        "citationCount", completion.citations().size(),
                        "citationsValid", completion.citationsValid()
                )
        );

        return toResponse(
                passportId,
                context.turnId(),
                preparation,
                provider,
                response,
                completion,
                citationRows,
                now
        );
    }

    public AnswerPassportResponse findByTurn(
            UUID turnId,
            UUID organizationId,
            UUID userId
    ) {
        List<PassportRow> rows = jdbc.query("""
                select id, chat_turn_id, retrieval_run_id, knowledge_base_id,
                       knowledge_mode, provider, requested_model,
                       resolved_model, embedding_model, context_sha256,
                       answer_sha256, evidence_sufficient, citations_valid,
                       created_at
                from knowledge_answer_passports
                where chat_turn_id = ?
                  and organization_id = ?
                  and user_id = ?
                """, this::mapPassport, turnId, organizationId, userId);
        if (rows.isEmpty()) {
            return null;
        }
        PassportRow row = rows.getFirst();
        List<AnswerCitationResponse> citations = jdbc.query("""
                select citation.citation_label,
                       chunk.id as chunk_id,
                       chunk.document_id,
                       chunk.document_version_id,
                       document.name as document_name,
                       version.version_number,
                       chunk.ordinal as chunk_ordinal,
                       chunk.page_from,
                       chunk.page_to,
                       chunk.heading,
                       chunk.content_sha256
                from knowledge_answer_citations citation
                join knowledge_document_chunks chunk
                  on chunk.id = citation.chunk_id
                 and chunk.knowledge_base_id = citation.knowledge_base_id
                 and chunk.organization_id = citation.organization_id
                join knowledge_documents document
                  on document.id = chunk.document_id
                 and document.knowledge_base_id = chunk.knowledge_base_id
                 and document.organization_id = chunk.organization_id
                join knowledge_document_versions version
                  on version.id = chunk.document_version_id
                 and version.document_id = chunk.document_id
                 and version.knowledge_base_id = chunk.knowledge_base_id
                 and version.organization_id = chunk.organization_id
                where citation.answer_passport_id = ?
                order by citation.ordinal
                """, this::mapCitation, row.id());
        return new AnswerPassportResponse(
                row.id(),
                row.chatTurnId(),
                row.retrievalRunId(),
                row.knowledgeBaseId(),
                row.mode(),
                row.provider(),
                row.requestedModel(),
                row.resolvedModel(),
                row.embeddingModel(),
                row.contextSha256(),
                row.answerSha256(),
                row.evidenceSufficient(),
                row.citationsValid(),
                row.createdAt(),
                citations
        );
    }

    public AnswerPassportResponse requireByTurn(
            UUID chatId,
            UUID turnId,
            UUID organizationId,
            UUID userId
    ) {
        List<UUID> matches = jdbc.queryForList("""
                select id
                from knowledge_answer_passports
                where chat_id = ?
                  and chat_turn_id = ?
                  and organization_id = ?
                  and user_id = ?
                """, UUID.class, chatId, turnId, organizationId, userId);
        if (matches.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Answer Passport не найден."
            );
        }
        return findByTurn(turnId, organizationId, userId);
    }

    private AnswerPassportResponse toResponse(
            UUID passportId,
            UUID turnId,
            RagPreparation preparation,
            String provider,
            AiChatResponse response,
            RagCompletion completion,
            List<CitationRow> rows,
            Instant now
    ) {
        List<AnswerCitationResponse> citations = new ArrayList<>();
        rows.forEach(row -> {
            var hit = row.source().hit();
            citations.add(new AnswerCitationResponse(
                    row.citation().label(),
                    hit.chunkId(),
                    hit.documentId(),
                    hit.documentVersionId(),
                    hit.documentName(),
                    hit.versionNumber(),
                    hit.chunkOrdinal(),
                    hit.pageFrom(),
                    hit.pageTo(),
                    hit.heading(),
                    hit.contentSha256()
            ));
        });
        return new AnswerPassportResponse(
                passportId,
                turnId,
                preparation.retrievalRunId(),
                preparation.knowledgeBaseId(),
                preparation.mode(),
                provider,
                response.requestedModel(),
                response.model(),
                preparation.embeddingModel(),
                preparation.contextSha256(),
                KnowledgeHashing.sha256(response.content()),
                completion.evidenceSufficient(),
                completion.citationsValid(),
                now,
                citations
        );
    }

    private PassportRow mapPassport(ResultSet rs, int rowNumber)
            throws SQLException {
        return new PassportRow(
                rs.getObject("id", UUID.class),
                rs.getObject("chat_turn_id", UUID.class),
                rs.getObject("retrieval_run_id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class),
                KnowledgeMode.valueOf(rs.getString("knowledge_mode")),
                rs.getString("provider"),
                rs.getString("requested_model"),
                rs.getString("resolved_model"),
                rs.getString("embedding_model"),
                rs.getString("context_sha256"),
                rs.getString("answer_sha256"),
                rs.getBoolean("evidence_sufficient"),
                rs.getBoolean("citations_valid"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private AnswerCitationResponse mapCitation(ResultSet rs, int rowNumber)
            throws SQLException {
        return new AnswerCitationResponse(
                rs.getString("citation_label"),
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("document_version_id", UUID.class),
                rs.getString("document_name"),
                rs.getInt("version_number"),
                rs.getInt("chunk_ordinal"),
                nullableInteger(rs, "page_from"),
                nullableInteger(rs, "page_to"),
                rs.getString("heading"),
                rs.getString("content_sha256")
        );
    }

    private static Integer nullableInteger(ResultSet rs, String column)
            throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private record CitationRow(
            RagCitation citation,
            KnowledgeContextSource source
    ) {
    }

    private record PassportRow(
            UUID id,
            UUID chatTurnId,
            UUID retrievalRunId,
            UUID knowledgeBaseId,
            KnowledgeMode mode,
            String provider,
            String requestedModel,
            String resolvedModel,
            String embeddingModel,
            String contextSha256,
            String answerSha256,
            boolean evidenceSufficient,
            boolean citationsValid,
            Instant createdAt
    ) {
    }
}
