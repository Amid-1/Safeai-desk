package ru.safeai.gateway.ai.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ModelExecutionPlanRepository extends JpaRepository<ModelExecutionPlanEntity, UUID> {
    Optional<ModelExecutionPlanEntity> findByProviderOperationId(UUID providerOperationId);
}
