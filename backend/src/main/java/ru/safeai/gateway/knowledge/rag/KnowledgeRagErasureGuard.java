// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/knowledge/rag/KnowledgeRagErasureGuard.java
package ru.safeai.gateway.knowledge.rag;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Проверяет, что уже материализованный RAG-контекст всё ещё допустим к использованию.
 *
 * <p>Вызывающий обязан открыть PostgreSQL read-write транзакцию: блокировки
 * FOR SHARE удерживаются до её завершения. Проверка не является execution lease
 * и не охватывает время внешнего сетевого вызова к AI-провайдеру.</p>
 */
final class KnowledgeRagErasureGuard {

    private KnowledgeRagErasureGuard() {
    }

    static void assertAvailable(
            JdbcTemplate jdbc,
            RagPreparation preparation,
            UUID organizationId
    ) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(organizationId, "organizationId");
        if (!preparation.mode().usesKnowledge()) {
            return;
        }

        // Один и тот же порядок блокировок: сначала база, затем документы по UUID.
        List<String> availableBases = jdbc.queryForList("""
                SELECT b.lifecycle_state FROM public.knowledge_bases b
                WHERE b.id=? AND b.organization_id=? AND b.enabled
                  AND b.lifecycle_state='AVAILABLE'
                FOR SHARE OF b
                """, String.class, preparation.knowledgeBaseId(), organizationId);
        if (availableBases.size() != 1) {
            throw new KnowledgeRagContextRevokedException();
        }

        List<SourceVersion> versions = preparation.sources().stream()
                .map(source -> {
                    if (source == null || source.hit() == null) {
                        throw new KnowledgeRagContextRevokedException();
                    }
                    return new SourceVersion(source.hit().documentId(), source.hit().documentVersionId());
                })
                .distinct()
                .sorted(Comparator.comparing(SourceVersion::documentId)
                        .thenComparing(SourceVersion::versionId))
                .toList();

        for (SourceVersion version : versions) {
            // Проверяется конкретная версия, использованная при формировании AI-контекста.
            // FOR SHARE сериализует финализацию с переходом документа в DELETING.
            List<UUID> matching = jdbc.queryForList("""
                    SELECT d.id FROM public.knowledge_documents d
                    JOIN public.knowledge_document_versions v
                      ON v.document_id=d.id AND v.organization_id=d.organization_id
                     AND v.knowledge_base_id=d.knowledge_base_id
                    WHERE d.id=? AND d.organization_id=? AND d.knowledge_base_id=?
                      AND d.enabled AND d.lifecycle_state='AVAILABLE' AND v.id=?
                    FOR SHARE OF d, v
                    """, UUID.class, version.documentId(), organizationId,
                    preparation.knowledgeBaseId(), version.versionId());
            if (matching.size() != 1 || !version.documentId().equals(matching.getFirst())) {
                throw new KnowledgeRagContextRevokedException();
            }
        }
    }

    private record SourceVersion(UUID documentId, UUID versionId) {
        private SourceVersion {
            if (documentId == null || versionId == null) {
                throw new KnowledgeRagContextRevokedException();
            }
        }
    }
}
