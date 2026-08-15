package ru.safeai.gateway.knowledge.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, UUID> {
    Page<KnowledgeDocumentEntity> findAllByKnowledgeBaseIdAndOrganizationId(
            UUID knowledgeBaseId,
            UUID organizationId,
            Pageable pageable
    );

    Page<KnowledgeDocumentEntity> findAllByKnowledgeBaseIdAndOrganizationIdAndEnabledTrue(
            UUID knowledgeBaseId,
            UUID organizationId,
            Pageable pageable
    );

    Optional<KnowledgeDocumentEntity> findByIdAndKnowledgeBaseIdAndOrganizationId(
            UUID id,
            UUID knowledgeBaseId,
            UUID organizationId
    );

    boolean existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
            UUID knowledgeBaseId,
            UUID organizationId,
            String name
    );

    @Query("select coalesce(max(v.versionNumber),0) " +
            "from KnowledgeDocumentVersionEntity v where v.documentId=:documentId and v.organizationId=:organizationId")
    int currentVersionNumber(
            @Param("documentId") UUID documentId,
            @Param("organizationId") UUID organizationId);
}
