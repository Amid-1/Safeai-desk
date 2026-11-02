// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/knowledge/rag/KnowledgeRagErasureGuardTest.java
package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class KnowledgeRagErasureGuardTest {

    @Mock JdbcTemplate jdbc;

    @Test
    void generalModeDoesNotQueryKnowledge() {
        KnowledgeRagErasureGuard.assertAvailable(jdbc, RagPreparation.general(request(null, null)), ORG_ID);
        verifyNoInteractions(jdbc);
    }

    @Test
    void deletingOrMissingBaseBlocksAiContextEvenWithoutSources() {
        var prepared = preparation(KnowledgeMode.KNOWLEDGE_ASSISTED, 0);
        assertThatThrownBy(() -> KnowledgeRagErasureGuard.assertAvailable(jdbc, prepared, ORG_ID))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
    }

    @Test
    void deletingDocumentBlocksPreviouslyMaterializedSource() {
        var prepared = preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(KB_ID), eq(ORG_ID)))
                .thenReturn(List.of("AVAILABLE"));
        // Вторая выборка возвращает пустой список, если документ уже DELETING.
        assertThatThrownBy(() -> KnowledgeRagErasureGuard.assertAvailable(jdbc, prepared, ORG_ID))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
    }

    @Test
    void availableBaseAndExactDocumentVersionAllowUse() {
        var prepared = preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1);
        var hit = prepared.sources().getFirst().hit();
        when(jdbc.queryForList(anyString(), eq(String.class), eq(KB_ID), eq(ORG_ID)))
                .thenReturn(List.of("AVAILABLE"));
        when(jdbc.queryForList(anyString(), eq(UUID.class),
                eq(hit.documentId()), eq(ORG_ID), eq(KB_ID), eq(hit.documentVersionId())))
                .thenReturn(List.of(hit.documentId()));
        KnowledgeRagErasureGuard.assertAvailable(jdbc, prepared, ORG_ID);
        verify(jdbc).queryForList(anyString(), eq(UUID.class),
                eq(hit.documentId()), eq(ORG_ID), eq(KB_ID), eq(hit.documentVersionId()));
    }

    @Test
    void databaseFailureIsNotInterpretedAsPermissionToUseContext() {
        var prepared = preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(KB_ID), eq(ORG_ID)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB unreachable"));
        assertThatThrownBy(() -> KnowledgeRagErasureGuard.assertAvailable(jdbc, prepared, ORG_ID))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }

    @Test
    void anotherVersionCannotAuthorizeStaleContext() {
        var prepared = preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1);
        var hit = prepared.sources().getFirst().hit();
        when(jdbc.queryForList(anyString(), eq(String.class), eq(KB_ID), eq(ORG_ID)))
                .thenReturn(List.of("AVAILABLE"));
        when(jdbc.queryForList(anyString(), eq(UUID.class),
                eq(hit.documentId()), eq(ORG_ID), eq(KB_ID), eq(hit.documentVersionId())))
                .thenReturn(List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> KnowledgeRagErasureGuard.assertAvailable(jdbc, prepared, ORG_ID))
                .isInstanceOf(KnowledgeRagContextRevokedException.class);
    }
}
