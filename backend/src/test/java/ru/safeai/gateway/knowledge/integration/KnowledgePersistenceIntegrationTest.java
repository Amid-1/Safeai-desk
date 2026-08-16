package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "safeai.knowledge.storage.type=local",
        "safeai.knowledge.storage.local-root=target/test-knowledge-persistence",
        "safeai.knowledge.storage.max-upload-bytes=26214400"
})
class KnowledgePersistenceIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void uploadNew_persistsDocumentVersionAndPendingIngestionJob() {
        TestIdentity identity = createTenant("upload");

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Production Runbooks",
                        "Integration test",
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                identity.adminPrincipal()
        );

        byte[] payload =
                "ORION-DELTA-7421 integration"
                        .getBytes(StandardCharsets.UTF_8);

        var response = knowledgeDocumentService.uploadNew(
                kb.id(),
                "Runbook",
                new MockMultipartFile(
                        "file",
                        "runbook.txt",
                        "application/octet-stream",
                        payload
                ),
                identity.adminPrincipal()
        );

        var document = jdbcTemplate.queryForMap(
                """
                select
                    organization_id,
                    knowledge_base_id,
                    name,
                    enabled,
                    current_version_id,
                    created_by_user_id,
                    version
                from public.knowledge_documents
                where id = ?
                """,
                response.id()
        );

        assertThat(document.get("organization_id"))
                .isEqualTo(identity.organizationId());
        assertThat(document.get("knowledge_base_id"))
                .isEqualTo(kb.id());
        assertThat(document.get("name"))
                .isEqualTo("Runbook");
        assertThat(document.get("enabled"))
                .isEqualTo(true);
        assertThat(document.get("current_version_id"))
                .isEqualTo(response.currentVersionId());
        assertThat(document.get("created_by_user_id"))
                .isEqualTo(identity.adminId());

        var version = jdbcTemplate.queryForMap(
                """
                select
                    version_number,
                    original_filename,
                    media_type,
                    size_bytes,
                    sha256,
                    storage_key,
                    created_by_user_id
                from public.knowledge_document_versions
                where id = ?
                """,
                response.currentVersionId()
        );

        assertThat(
                ((Number) Objects.requireNonNull(
                        version.get("version_number")
                )).intValue()
        ).isEqualTo(1);

        assertThat(version.get("original_filename"))
                .isEqualTo("runbook.txt");

        assertThat(version.get("media_type"))
                .isEqualTo("text/plain");

        assertThat(
                ((Number) Objects.requireNonNull(
                        version.get("size_bytes")
                )).longValue()
        ).isEqualTo(payload.length);

        assertThat((String) version.get("sha256"))
                .hasSize(64);

        assertThat((String) version.get("storage_key"))
                .startsWith(
                        identity.organizationId()
                                + "/"
                                + kb.id()
                                + "/"
                                + response.id()
                                + "/"
                );

        assertThat(version.get("created_by_user_id"))
                .isEqualTo(identity.adminId());

        String status = jdbcTemplate.queryForObject(
                """
                select status
                from public.knowledge_ingestion_jobs
                where document_version_id = ?
                  and organization_id = ?
                """,
                String.class,
                response.currentVersionId(),
                identity.organizationId()
        );

        assertThat(status)
                .isEqualTo(
                        KnowledgeIngestionStatus.PENDING.name()
                );
    }

    @Test
    void uploadVersion_keepsPreviousVersionAndMovesCurrentPointer() {
        TestIdentity identity = createTenant("version");

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Versioned KB",
                        null,
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                identity.adminPrincipal()
        );

        var v1 = knowledgeDocumentService.uploadNew(
                kb.id(),
                "Runbook",
                textFile(
                        "runbook-v1.txt",
                        "version one"
                ),
                identity.adminPrincipal()
        );

        var v2 = knowledgeDocumentService.uploadVersion(
                kb.id(),
                v1.id(),
                textFile(
                        "runbook-v2.txt",
                        "version two"
                ),
                identity.adminPrincipal()
        );

        assertThat(v1.versionNumber()).isEqualTo(1);
        assertThat(v2.versionNumber()).isEqualTo(2);
        assertThat(v2.currentVersionId())
                .isNotEqualTo(v1.currentVersionId());

        Integer versionCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.knowledge_document_versions
                where document_id = ?
                  and organization_id = ?
                """,
                Integer.class,
                v1.id(),
                identity.organizationId()
        );

        assertThat(versionCount).isEqualTo(2);

        UUID currentVersionId = jdbcTemplate.queryForObject(
                """
                select current_version_id
                from public.knowledge_documents
                where id = ?
                """,
                UUID.class,
                v1.id()
        );

        assertThat(currentVersionId)
                .isEqualTo(v2.currentVersionId());

        assertThat(
                knowledgeDocumentService.download(
                        kb.id(),
                        v1.id(),
                        v1.currentVersionId(),
                        identity.adminPrincipal()
                ).filename()
        ).isEqualTo("runbook-v1.txt");

        assertThat(
                knowledgeDocumentService.download(
                        kb.id(),
                        v1.id(),
                        null,
                        identity.adminPrincipal()
                ).filename()
        ).isEqualTo("runbook-v2.txt");
    }

    @Test
    void duplicateDocumentName_isRejectedCaseInsensitively() {
        TestIdentity identity = createTenant("duplicate");

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Duplicate KB",
                        null,
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                identity.adminPrincipal()
        );

        knowledgeDocumentService.uploadNew(
                kb.id(),
                "RunBook",
                textFile(
                        "a.txt",
                        "a"
                ),
                identity.adminPrincipal()
        );

        assertThatThrownBy(
                () -> knowledgeDocumentService.uploadNew(
                        kb.id(),
                        "runbook",
                        textFile(
                                "b.txt",
                                "b"
                        ),
                        identity.adminPrincipal()
                )
        )
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже существует");
    }

    @Test
    void tenantCannotReadKnowledgeBaseFromAnotherOrganization() {
        TestIdentity owner = createTenant("owner");
        TestIdentity foreign = createTenant("foreign");

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Tenant private KB",
                        null,
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                owner.adminPrincipal()
        );

        var document =
                knowledgeDocumentService.uploadNew(
                        kb.id(),
                        "Secret",
                        textFile(
                                "secret.txt",
                                "tenant secret"
                        ),
                        owner.adminPrincipal()
                );

        assertThatThrownBy(
                () -> knowledgeDocumentService.download(
                        kb.id(),
                        document.id(),
                        null,
                        foreign.adminPrincipal()
                )
        )
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("База знаний");
    }

    @Test
    void persistedVersionRow_isImmutableAtDatabaseLevel() {
        TestIdentity identity = createTenant("immutable");

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Immutable KB",
                        null,
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                identity.adminPrincipal()
        );

        var document = knowledgeDocumentService.uploadNew(
                kb.id(),
                "Immutable",
                textFile(
                        "immutable.txt",
                        "v1"
                ),
                identity.adminPrincipal()
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        update public.knowledge_document_versions
                        set original_filename = 'mutated.txt'
                        where id = ?
                        """,
                        document.currentVersionId()
                )
        ).isInstanceOf(
                org.springframework.dao.DataAccessException.class
        );
    }

    private TestIdentity createTenant(
            String suffix
    ) {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Knowledge Test Org "
                        + suffix
                        + " "
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8),
                true
        );

        insertUser(
                adminId,
                organizationId,
                "knowledge-admin-"
                        + suffix
                        + "-"
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                        + "@test.local",
                true,
                "ADMIN",
                Instant.now().minusSeconds(60)
        );

        return new TestIdentity(
                organizationId,
                adminId,
                adminPrincipal(
                        adminId,
                        organizationId
                )
        );
    }

    private static MockMultipartFile textFile(
            String filename,
            String content
    ) {
        return new MockMultipartFile(
                "file",
                filename,
                "text/plain",
                content.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    private static SafeAiUserPrincipal adminPrincipal(
            UUID userId,
            UUID organizationId
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId,
                organizationId,
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                ROLE_ADMIN
                        )
                )
        );
    }

    private record TestIdentity(
            UUID organizationId,
            UUID adminId,
            SafeAiUserPrincipal adminPrincipal
    ) {
    }
}