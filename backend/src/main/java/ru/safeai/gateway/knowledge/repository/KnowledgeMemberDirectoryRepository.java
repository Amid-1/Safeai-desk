package ru.safeai.gateway.knowledge.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.knowledge.dto.KnowledgeMemberCandidateResponse;
import ru.safeai.gateway.user.entity.UserEntity;

import java.util.List;
import java.util.UUID;

public interface KnowledgeMemberDirectoryRepository
        extends Repository<UserEntity, UUID> {

    @Query("""
            select new ru.safeai.gateway.knowledge.dto.KnowledgeMemberCandidateResponse(
                user.id,
                user.email,
                user.fullName
            )
            from UserEntity user
            where user.organization.id = :organizationId
              and user.enabled = true
              and (
                    :query = ''
                    or locate(
                        :query,
                        lower(user.email)
                    ) = 1
                    or locate(
                        :query,
                        lower(
                            coalesce(
                                user.fullName,
                                ''
                            )
                        )
                    ) > 0
              )
            order by
                lower(user.email) asc,
                user.id asc
            """)
    List<KnowledgeMemberCandidateResponse> search(
            @Param("organizationId")
            UUID organizationId,
            @Param("query")
            String query,
            Pageable pageable
    );
}