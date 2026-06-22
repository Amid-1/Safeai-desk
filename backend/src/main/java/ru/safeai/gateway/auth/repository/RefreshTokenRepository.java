package ru.safeai.gateway.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);
}