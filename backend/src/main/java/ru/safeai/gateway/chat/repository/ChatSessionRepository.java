package ru.safeai.gateway.chat.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository
        extends JpaRepository<ChatSessionEntity, UUID> {

    Slice<ChatSessionEntity> findByUser_IdAndOrganization_Id(
            UUID userId,
            UUID organizationId,
            Pageable pageable
    );

    Optional<ChatSessionEntity> findByIdAndUser_IdAndOrganization_Id(
            UUID id,
            UUID userId,
            UUID organizationId
    );

    boolean existsByIdAndUser_IdAndOrganization_Id(
            UUID id,
            UUID userId,
            UUID organizationId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChatSessionEntity session
               set session.updatedAt = :updatedAt
             where session.id = :chatId
               and session.user.id = :userId
               and session.organization.id = :organizationId
            """)
    int touchOwned(
            @Param("chatId") UUID chatId,
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId,
            @Param("updatedAt") Instant updatedAt
    );
}
