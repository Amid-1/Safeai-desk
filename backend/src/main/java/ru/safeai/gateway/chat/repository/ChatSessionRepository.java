package ru.safeai.gateway.chat.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;

import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, UUID> {

    Page<ChatSessionEntity> findByUser_IdAndOrganization_IdOrderByUpdatedAtDesc(
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
}