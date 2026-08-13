package ru.safeai.gateway.knowledge.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeBaseRepository
        extends JpaRepository<KnowledgeBaseEntity, UUID> {

    Page<KnowledgeBaseEntity> findAllByOrganizationId(
            UUID organizationId,
            Pageable pageable
    );

    Optional<KnowledgeBaseEntity> findByIdAndOrganizationId(
            UUID id,
            UUID organizationId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select kb
            from KnowledgeBaseEntity kb
            where kb.id = :id
              and kb.organizationId = :organizationId
            """)
    Optional<KnowledgeBaseEntity> findForUpdate(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId
    );

    @Query("""
            select kb
            from KnowledgeBaseEntity kb
            where kb.organizationId = :organizationId
              and kb.enabled = true
              and (
                    kb.visibility = :organizationVisibility
                    or exists (
                        select membership.id
                        from KnowledgeBaseMembershipEntity membership
                        where membership.knowledgeBaseId = kb.id
                          and membership.organizationId = :organizationId
                          and membership.userId = :userId
                    )
              )
            """)
    Page<KnowledgeBaseEntity> findVisibleForUser(
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId,
            @Param("organizationVisibility")
            KnowledgeBaseVisibility organizationVisibility,
            Pageable pageable
    );

    boolean existsByOrganizationIdAndNameIgnoreCase(
            UUID organizationId,
            String name
    );

    boolean existsByOrganizationIdAndNameIgnoreCaseAndIdNot(
            UUID organizationId,
            String name,
            UUID id
    );
}
