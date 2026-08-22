package ru.safeai.gateway.knowledge.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository
        extends JpaRepository<KnowledgeDocumentEntity, UUID> {

    Page<KnowledgeDocumentEntity> findAllByKnowledgeBaseIdAndOrganizationId(
            UUID knowledgeBaseId,
            UUID organizationId,
            Pageable pageable
    );

    @Query("""
            select document
            from KnowledgeDocumentEntity document
            where document.id = :documentId
              and document.knowledgeBaseId = :knowledgeBaseId
              and document.organizationId = :organizationId
            """)
    Optional<KnowledgeDocumentEntity> findByIdAndKnowledgeBaseIdAndOrganizationId(
            @Param("documentId") UUID documentId,
            @Param("knowledgeBaseId") UUID knowledgeBaseId,
            @Param("organizationId") UUID organizationId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select document
            from KnowledgeDocumentEntity document
            where document.id = :documentId
              and document.knowledgeBaseId = :knowledgeBaseId
              and document.organizationId = :organizationId
            """)
    Optional<KnowledgeDocumentEntity> findForUpdate(
            @Param("documentId") UUID documentId,
            @Param("knowledgeBaseId") UUID knowledgeBaseId,
            @Param("organizationId") UUID organizationId
    );

    boolean existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
            UUID knowledgeBaseId,
            UUID organizationId,
            String name
    );

    @Query("""
            select coalesce(max(documentVersion.versionNumber), 0)
            from KnowledgeDocumentVersionEntity documentVersion
            where documentVersion.documentId = :documentId
              and documentVersion.organizationId = :organizationId
            """)
    int currentVersionNumber(
            @Param("documentId") UUID documentId,
            @Param("organizationId") UUID organizationId
    );
}