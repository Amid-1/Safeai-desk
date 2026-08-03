package ru.safeai.gateway.chat.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;

import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository
        extends JpaRepository<ChatMessageEntity, UUID> {

    @Query("""
            select message
              from ChatMessageEntity message
             where message.session.id = :sessionId
               and message.role in (
                    ru.safeai.gateway.chat.entity.ChatMessageRole.USER,
                    ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT
               )
             order by message.createdAt desc, message.id desc
            """)
    Slice<ChatMessageEntity> findVisibleBySessionId(
            @Param("sessionId") UUID sessionId,
            Pageable pageable
    );

    @Query("""
            select message
              from ChatMessageEntity message
             where message.id = :messageId
               and message.session.id = :sessionId
               and message.organization.id = :organizationId
            """)
    Optional<ChatMessageEntity> findOwnedMessage(
            @Param("messageId") UUID messageId,
            @Param("sessionId") UUID sessionId,
            @Param("organizationId") UUID organizationId
    );
}