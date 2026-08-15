package ru.safeai.gateway.knowledge.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseMemberResponse;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseMembershipEntity;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeBaseMembershipRepository
        extends JpaRepository<KnowledgeBaseMembershipEntity, UUID> {

    boolean existsByKnowledgeBaseIdAndOrganizationIdAndUserId(
            UUID knowledgeBaseId,
            UUID organizationId,
            UUID userId
    );

    Optional<KnowledgeBaseMembershipEntity> findByKnowledgeBaseIdAndOrganizationIdAndUserId(
            UUID knowledgeBaseId, UUID organizationId, UUID userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select membership
            from KnowledgeBaseMembershipEntity membership
            where membership.knowledgeBaseId = :knowledgeBaseId
              and membership.organizationId = :organizationId
              and membership.userId = :userId
            """)
    Optional<KnowledgeBaseMembershipEntity> findForUpdate(
            @Param("knowledgeBaseId") UUID knowledgeBaseId,
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId
    );

    @Query(
            value = """
                    select new ru.safeai.gateway.knowledge.dto.KnowledgeBaseMemberResponse(
                        membership.knowledgeBaseId,
                        membership.userId,
                        user.email,
                        user.fullName,
                        membership.accessLevel,
                        membership.version,
                        membership.createdAt,
                        membership.updatedAt
                    )
                    from KnowledgeBaseMembershipEntity membership,
                         UserEntity user
                    where membership.knowledgeBaseId = :knowledgeBaseId
                      and membership.organizationId = :organizationId
                      and user.id = membership.userId
                      and user.organization.id = membership.organizationId
                    order by user.email asc, membership.userId asc
                    """,
            countQuery = """
                    select count(membership)
                    from KnowledgeBaseMembershipEntity membership
                    where membership.knowledgeBaseId = :knowledgeBaseId
                      and membership.organizationId = :organizationId
                    """
    )
    Page<KnowledgeBaseMemberResponse> findMemberResponses(
            @Param("knowledgeBaseId") UUID knowledgeBaseId,
            @Param("organizationId") UUID organizationId,
            Pageable pageable
    );
}
