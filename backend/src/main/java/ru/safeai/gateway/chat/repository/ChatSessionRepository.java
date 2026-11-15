package ru.safeai.gateway.chat.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.entity.ChatErasureState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository
        extends JpaRepository<ChatSessionEntity, UUID> {

    Slice<ChatSessionEntity> findByUser_IdAndOrganization_IdAndArchivedAtIsNull(
            UUID userId,
            UUID organizationId,
            Pageable pageable
    );

    Optional<ChatSessionEntity> findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
            UUID id,
            UUID userId,
            UUID organizationId
    );

    boolean existsByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(
            UUID id,
            UUID userId,
            UUID organizationId
    );


    Slice<ChatSessionEntity> findByUser_IdAndOrganization_IdAndArchivedAtIsNotNullAndErasureState(
            UUID userId, UUID organizationId, ChatErasureState erasureState, Pageable pageable);

    Optional<ChatSessionEntity> findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNotNullAndErasureState(
            UUID chatId, UUID userId, UUID organizationId, ChatErasureState erasureState);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSessionEntity s where s.id=:chatId and s.user.id=:userId "
         + "and s.organization.id=:organizationId and s.erasureState=:state")
    Optional<ChatSessionEntity> findOwnedAvailableForUpdate(
            @Param("chatId") UUID chatId, @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId,
        @Param("state") ChatErasureState state);

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
