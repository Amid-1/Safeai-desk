package ru.safeai.gateway.knowledge.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository
        extends JpaRepository<KnowledgeDocumentEntity, UUID> {

    @Query("""
            select document
            from KnowledgeDocumentEntity document
            where document.id = :documentId
              and document.knowledgeBaseId = :knowledgeBaseId
              and document.organizationId = :organizationId
            """)
    Optional<KnowledgeDocumentEntity>
    findByIdAndKnowledgeBaseIdAndOrganizationId(
            @Param("documentId")
            UUID documentId,
            @Param("knowledgeBaseId")
            UUID knowledgeBaseId,
            @Param("organizationId")
            UUID organizationId
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
            @Param("documentId")
            UUID documentId,
            @Param("knowledgeBaseId")
            UUID knowledgeBaseId,
            @Param("organizationId")
            UUID organizationId
    );

    boolean existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(
            UUID knowledgeBaseId,
            UUID organizationId,
            String name
    );

    boolean existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
            UUID knowledgeBaseId,
            UUID organizationId,
            String name,
            UUID id
    );

    @Query(
            value = """
                    select coalesce(
                        max(document_version.version_number),
                        0
                    )
                    from knowledge_document_versions document_version
                    where document_version.document_id = :documentId
                      and document_version.knowledge_base_id = :knowledgeBaseId
                      and document_version.organization_id = :organizationId
                    """,
            nativeQuery = true
    )
    int currentVersionNumber(
            @Param("documentId")
            UUID documentId,
            @Param("knowledgeBaseId")
            UUID knowledgeBaseId,
            @Param("organizationId")
            UUID organizationId
    );
}