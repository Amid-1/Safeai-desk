package ru.safeai.gateway.chat.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    List<ChatMessageEntity> findBySession_IdOrderByCreatedAtAscIdAsc(UUID sessionId);

    Page<ChatMessageEntity> findBySession_IdOrderByCreatedAtAscIdAsc(
            UUID sessionId,
            Pageable pageable
    );

    List<ChatMessageEntity> findTop30BySession_IdOrderByCreatedAtDescIdDesc(UUID sessionId);
}