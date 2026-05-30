package ru.safeai.gateway.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.user.entity.RoleEntity;

import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    Optional<RoleEntity> findByName(String name);
}