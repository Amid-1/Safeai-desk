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
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeMemberCandidateResponse;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        "safeai.knowledge.storage.local-root=target/test-knowledge-access"
})
class KnowledgeAccessControlIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void membersVisibility_viewerCannotUpload_editorCanUpload() {
        Tenant tenant = createTenant();

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Members KB",
                        null,
                        KnowledgeBaseVisibility.MEMBERS
                ),
                tenant.adminPrincipal()
        );

        assertThatThrownBy(
                () -> knowledgeBaseService.findById(
                        kb.id(),
                        tenant.userPrincipal()
                )
        )
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("База знаний не найдена");

        var membership = knowledgeBaseService.addMember(
                kb.id(),
                new CreateKnowledgeBaseMemberRequest(
                        tenant.userId(),
                        KnowledgeBaseAccessLevel.VIEWER
                ),
                tenant.adminPrincipal()
        );

        assertThat(
                knowledgeBaseService.findById(
                        kb.id(),
                        tenant.userPrincipal()
                ).id()
        ).isEqualTo(kb.id());

        assertThatThrownBy(
                () -> knowledgeDocumentService.uploadNew(
                        kb.id(),
                        "Viewer forbidden",
                        textFile(
                                "viewer.txt",
                                "viewer"
                        ),
                        tenant.userPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Недостаточно прав");

        var editorMembership =
                knowledgeBaseService.updateMember(
                        kb.id(),
                        tenant.userId(),
                        new UpdateKnowledgeBaseMemberRequest(
                                KnowledgeBaseAccessLevel.EDITOR,
                                membership.version()
                        ),
                        tenant.adminPrincipal()
                );

        assertThat(editorMembership.accessLevel())
                .isEqualTo(KnowledgeBaseAccessLevel.EDITOR);

        var uploaded = knowledgeDocumentService.uploadNew(
                kb.id(),
                "Editor allowed",
                textFile(
                        "editor.txt",
                        "editor"
                ),
                tenant.userPrincipal()
        );

        assertThat(uploaded.id()).isNotNull();
        assertThat(uploaded.versionNumber()).isEqualTo(1);
    }

    @Test
    void organizationVisibility_givesImplicitViewerButNotEditor() {
        Tenant tenant = createTenant();

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Organization KB",
                        null,
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                tenant.adminPrincipal()
        );

        assertThat(
                knowledgeBaseService.findById(
                        kb.id(),
                        tenant.userPrincipal()
                ).id()
        ).isEqualTo(kb.id());

        assertThat(
                knowledgeDocumentService.list(
                        kb.id(),
                        tenant.userPrincipal(),
                        0,
                        50
                ).content()
        ).isEmpty();

        assertThatThrownBy(
                () -> knowledgeDocumentService.uploadNew(
                        kb.id(),
                        null,
                        textFile(
                                "implicit-viewer.txt",
                                "data"
                        ),
                        tenant.userPrincipal()
                )
        ).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void disabledKnowledgeBase_isHiddenFromUserButVisibleToAdmin() {
        Tenant tenant = createTenant();

        var created = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Disabled KB",
                        "description",
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                tenant.adminPrincipal()
        );

        var disabled = knowledgeBaseService.update(
                created.id(),
                new UpdateKnowledgeBaseRequest(
                        created.name(),
                        created.description(),
                        created.visibility(),
                        false,
                        created.version()
                ),
                tenant.adminPrincipal()
        );

        assertThat(disabled.enabled()).isFalse();

        assertThatThrownBy(
                () -> knowledgeBaseService.findById(
                        created.id(),
                        tenant.userPrincipal()
                )
        ).isInstanceOf(ResourceNotFoundException.class);

        assertThat(
                knowledgeBaseService.findById(
                        created.id(),
                        tenant.adminPrincipal()
                ).enabled()
        ).isFalse();
    }

    @Test
    void memberCandidates_areTenantScopedAndExcludeDisabledUsers() {
        Tenant tenant = createTenant();

        UUID aliceId = UUID.randomUUID();
        UUID disabledAliceId = UUID.randomUUID();

        insertUser(
                aliceId,
                tenant.organizationId(),
                "alice.enabled@test.local",
                true,
                "USER",
                Instant.now().minusSeconds(30)
        );

        insertUser(
                disabledAliceId,
                tenant.organizationId(),
                "alice.disabled@test.local",
                false,
                "USER",
                Instant.now().minusSeconds(30)
        );

        jdbcTemplate.update(
                """
                update public.users
                set full_name = 'Alice Enabled'
                where id = ?
                """,
                aliceId
        );

        UUID foreignOrganization = UUID.randomUUID();
        UUID foreignAlice = UUID.randomUUID();

        insertOrganization(
                foreignOrganization,
                "Foreign Knowledge Org "
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8),
                true
        );

        insertUser(
                foreignAlice,
                foreignOrganization,
                "alice.foreign@test.local",
                true,
                "USER",
                Instant.now().minusSeconds(30)
        );

        var candidates =
                knowledgeBaseService.searchMemberCandidates(
                        " ALICE ",
                        50,
                        tenant.adminPrincipal()
                );

        assertThat(candidates)
                .extracting(
                        KnowledgeMemberCandidateResponse::userId
                )
                .contains(aliceId)
                .doesNotContain(
                        disabledAliceId,
                        foreignAlice
                );
    }

    @Test
    void removingMembership_revokesMembersOnlyVisibilityImmediately() {
        Tenant tenant = createTenant();

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Revocation KB",
                        null,
                        KnowledgeBaseVisibility.MEMBERS
                ),
                tenant.adminPrincipal()
        );

        var membership = knowledgeBaseService.addMember(
                kb.id(),
                new CreateKnowledgeBaseMemberRequest(
                        tenant.userId(),
                        KnowledgeBaseAccessLevel.VIEWER
                ),
                tenant.adminPrincipal()
        );

        assertThat(
                knowledgeBaseService.findById(
                        kb.id(),
                        tenant.userPrincipal()
                ).id()
        ).isEqualTo(kb.id());

        knowledgeBaseService.removeMember(
                kb.id(),
                tenant.userId(),
                membership.version(),
                tenant.adminPrincipal()
        );

        assertThatThrownBy(
                () -> knowledgeBaseService.findById(
                        kb.id(),
                        tenant.userPrincipal()
                )
        ).isInstanceOf(ResourceNotFoundException.class);
    }

    private Tenant createTenant() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Knowledge ACL Org "
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8),
                true
        );

        insertUser(
                adminId,
                organizationId,
                "kb-admin-"
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                        + "@test.local",
                true,
                "ADMIN",
                Instant.now().minusSeconds(60)
        );

        insertUser(
                userId,
                organizationId,
                "kb-user-"
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                        + "@test.local",
                true,
                "USER",
                Instant.now().minusSeconds(60)
        );

        return new Tenant(
                organizationId,
                adminId,
                userId,
                principal(
                        adminId,
                        organizationId,
                        "ROLE_ADMIN"
                ),
                principal(
                        userId,
                        organizationId,
                        "ROLE_USER"
                )
        );
    }

    private static SafeAiUserPrincipal principal(
            UUID userId,
            UUID organizationId,
            String role
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId,
                organizationId,
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(role)
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
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Tenant(
            UUID organizationId,
            UUID adminId,
            UUID userId,
            SafeAiUserPrincipal adminPrincipal,
            SafeAiUserPrincipal userPrincipal
    ) {
    }
}