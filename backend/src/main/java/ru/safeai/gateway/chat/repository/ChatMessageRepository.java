package ru.safeai.gateway.chat.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    Page<ChatMessageEntity> findBySession_IdOrderByCreatedAtAscIdAsc(
            UUID sessionId,
            Pageable pageable
    );

    List<ChatMessageEntity> findBySession_IdOrderByCreatedAtDescIdDesc(
            UUID sessionId,
            Pageable pageable
    );

    boolean existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
            UUID id,
            UUID sessionId,
            UUID userId,
            UUID organizationId,
            ChatMessageRole role
    );
}