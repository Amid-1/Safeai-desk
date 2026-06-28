package ru.safeai.gateway.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;

import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, UUID> {

    Page<ChatSessionEntity> findByUser_IdOrderByUpdatedAtDesc(UUID userId, Pageable pageable);

    Optional<ChatSessionEntity> findByIdAndUser_Id(UUID id, UUID userId);

    boolean existsByIdAndUser_Id(UUID id, UUID userId);
}