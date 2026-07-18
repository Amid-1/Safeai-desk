package ru.safeai.gateway.chat.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    Page<ChatMessageEntity> findBySession_IdOrderByCreatedAtDescIdDesc(
            UUID sessionId,
            Pageable pageable
    );

    @Query("""
            select message
            from ChatMessageEntity message
            where message.session.id = :sessionId
              and message.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and message.role in (
                  ru.safeai.gateway.chat.entity.ChatMessageRole.USER,
                  ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT,
                  ru.safeai.gateway.chat.entity.ChatMessageRole.SYSTEM
              )
            order by message.createdAt desc, message.id desc
            """)
    List<ChatMessageEntity> findCompletedHistoryForAi(
            @Param("sessionId") UUID sessionId,
            Pageable pageable
    );

    Optional<ChatMessageEntity> findBySession_IdAndClientRequestIdAndRole(
            UUID sessionId,
            UUID clientRequestId,
            ChatMessageRole role
    );

    Optional<ChatMessageEntity> findFirstBySession_IdAndReplyToMessageIdAndRoleOrderByCreatedAtDescIdDesc(
            UUID sessionId,
            UUID replyToMessageId,
            ChatMessageRole role
    );

    boolean existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
            UUID id,
            UUID sessionId,
            UUID userId,
            UUID organizationId,
            ChatMessageRole role
    );
}
