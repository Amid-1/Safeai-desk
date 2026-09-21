package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeReindexResponse;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentRepository;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class KnowledgeOperationsServiceRegressionTest {
    private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-4000-8000-000000000021");
    private static final UUID VERSION_ID = UUID.fromString("10000000-0000-4000-8000-000000000022");
    private static final UUID JOB_ID = UUID.fromString("10000000-0000-4000-8000-000000000023");
    @Mock KnowledgeAccessService access;
    @Mock KnowledgeDocumentRepository documents;
    @Mock KnowledgeEmbeddingProvider embedding;
    @Mock JdbcTemplate jdbc;
    @Mock AuditEventService audit;
    private KnowledgeOperationsService service;
    private SafeAiUserPrincipal admin;

    @BeforeEach
    void setUp() {
        service = new KnowledgeOperationsService(access, documents, embedding,
                jdbc, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        admin = SafeAiUserPrincipal.accessTokenPrincipal(USER_ID, ORG_ID, 0L, 0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void missingCurrentVersionRejectsReindexWithoutTouchingQueue() {
        stubDocument(null);
        assertThatThrownBy(() -> service.reindex(KB_ID, DOCUMENT_ID, admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining("текущей версии");
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void activeIngestionCannotBeResetByConcurrentReindex() {
        stubDocument(VERSION_ID);
        stubJob("CHUNKING");
        assertThatThrownBy(() -> service.reindex(KB_ID, DOCUMENT_ID, admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining("уже выполняется");
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void finishedJobReindexReusesImmutableVersionAndProducesPendingState() {
        stubDocument(VERSION_ID);
        stubJob("READY");
        when(jdbc.update(anyString(), any(), any(), eq(JOB_ID), eq("READY"))).thenReturn(1);
        KnowledgeReindexResponse result = service.reindex(KB_ID, DOCUMENT_ID, admin);
        assertThat(result.documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(result.status()).isEqualTo(KnowledgeIngestionStatus.PENDING);
        verify(audit).record(eq(admin), eq(ORG_ID),
                eq(AuditEventType.KNOWLEDGE_REINDEX_REQUESTED), anyMap());
    }

    @Test
    void lostUpdateCannotClaimSuccessfulReindex() {
        stubDocument(VERSION_ID);
        stubJob("READY");
        when(jdbc.update(anyString(), any(), any(), eq(JOB_ID), eq("READY"))).thenReturn(0);
        assertThatThrownBy(() -> service.reindex(KB_ID, DOCUMENT_ID, admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining("изменилось");
        verifyNoInteractions(audit);
    }

    private void stubDocument(UUID versionId) {
        var base = new KnowledgeBaseEntity();
        base.setId(KB_ID);
        base.setEnabled(true);
        lenient().when(access.requireAccess(KB_ID, admin, KnowledgeBaseAccessLevel.EDITOR))
                .thenReturn(new KnowledgeAccessService.Access(base,
                        KnowledgeBaseAccessLevel.EDITOR, false));
        var document = new KnowledgeDocumentEntity();
        document.setId(DOCUMENT_ID);
        document.setCurrentVersionId(versionId);
        when(documents.findForUpdate(DOCUMENT_ID, KB_ID, ORG_ID))
                .thenReturn(Optional.of(document));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubJob(String state) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(VERSION_ID),
                eq(DOCUMENT_ID), eq(KB_ID), eq(ORG_ID))).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getObject("id", UUID.class)).thenReturn(JOB_ID);
            when(row.getString("status")).thenReturn(state);
            return java.util.List.of(mapper.mapRow(row, 0));
        });
    }
}
