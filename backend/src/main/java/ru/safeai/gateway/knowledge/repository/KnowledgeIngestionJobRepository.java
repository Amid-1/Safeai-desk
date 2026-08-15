package ru.safeai.gateway.knowledge.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.knowledge.entity.KnowledgeIngestionJobEntity;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeIngestionJobRepository extends JpaRepository<KnowledgeIngestionJobEntity, UUID> {
    Optional<KnowledgeIngestionJobEntity> findByDocumentVersionIdAndOrganizationId(
            UUID documentVersionId,
            UUID organizationId);
}
