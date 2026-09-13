package ru.safeai.gateway.knowledge.dto;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.service.KnowledgeAccessService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseAccessResponseTest {

    @Test
    void editorCanManageDocumentsButNotAdministrativeSettings() {
        KnowledgeBaseEntity base = base();

        KnowledgeBaseAccessResponse response =
                KnowledgeBaseAccessResponse.from(
                        new KnowledgeAccessService.Access(
                                base,
                                KnowledgeBaseAccessLevel.EDITOR,
                                false
                        )
                );

        assertThat(response.knowledgeBaseId()).isEqualTo(base.getId());
        assertThat(response.accessLevel())
                .isEqualTo(KnowledgeBaseAccessLevel.EDITOR);
        assertThat(response.canEditDocuments()).isTrue();
        assertThat(response.canManageBase()).isFalse();
        assertThat(response.canManageMembers()).isFalse();
    }

    @Test
    void administratorReceivesEveryCapability() {
        KnowledgeBaseAccessResponse response =
                KnowledgeBaseAccessResponse.from(
                        new KnowledgeAccessService.Access(
                                base(),
                                KnowledgeBaseAccessLevel.OWNER,
                                true
                        )
                );

        assertThat(response.administrator()).isTrue();
        assertThat(response.canEditDocuments()).isTrue();
        assertThat(response.canManageBase()).isTrue();
        assertThat(response.canManageMembers()).isTrue();
    }

    private static KnowledgeBaseEntity base() {
        KnowledgeBaseEntity base = new KnowledgeBaseEntity();
        base.setId(UUID.randomUUID());
        return base;
    }
}
