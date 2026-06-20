package ru.safeai.gateway.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, UUID> {

    List<ChatSessionEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    Optional<ChatSessionEntity> findByIdAndUser_Id(UUID id, UUID userId);

    boolean existsByIdAndUser_Id(UUID id, UUID userId);
}