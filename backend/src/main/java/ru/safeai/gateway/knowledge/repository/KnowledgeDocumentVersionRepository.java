package ru.safeai.gateway.knowledge.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentVersionRepository extends JpaRepository<KnowledgeDocumentVersionEntity,
        UUID> {
    Optional<KnowledgeDocumentVersionEntity> findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId
            (UUID id,
             UUID documentId,
             UUID knowledgeBaseId,
             UUID organizationId);
}
