package ru.safeai.gateway.ai.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "model_execution_plans")
public class ModelExecutionPlanEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "provider_operation_id", nullable = false, unique = true)
    private UUID providerOperationId;

    @Column(name = "chat_turn_id", nullable = false, unique = true)
    private UUID chatTurnId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "model_route_decision_id")
    private UUID modelRouteDecisionId;

    @Column(name = "requested_model", nullable = false, length = 100)
    private String requestedModel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModelExecutionPlanEntity() {
    }

    public static ModelExecutionPlanEntity create(
            UUID providerOperationId,
            UUID chatTurnId,
            UUID organizationId,
            UUID modelRouteDecisionId,
            String requestedModel,
            Instant now
    ) {
        ModelExecutionPlanEntity plan =
                new ModelExecutionPlanEntity();

        plan.id = UUID.randomUUID();

        plan.providerOperationId = Objects.requireNonNull(
                providerOperationId,
                "providerOperationId не должен быть null"
        );

        plan.chatTurnId = Objects.requireNonNull(
                chatTurnId,
                "chatTurnId не должен быть null"
        );

        plan.organizationId = Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        plan.modelRouteDecisionId = modelRouteDecisionId;

        plan.requestedModel = requireRequestedModel(
                requestedModel
        );

        plan.createdAt = Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        return plan;
    }

    public void requireMatches(AiExecutionRequest request) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        boolean matches =
                providerOperationId.equals(
                        request.aiRequest().providerOperationId()
                )
                && chatTurnId.equals(
                        request.chatTurnId()
                )
                && organizationId.equals(
                        request.aiRequest().organizationId()
                )
                && Objects.equals(
                        modelRouteDecisionId,
                        request.modelRouteDecisionId()
                )
                && requestedModel.equals(
                        request.target().requestedPhysicalModel()
                );

        if (!matches) {
            throw new IllegalStateException(
                    "Persisted execution plan does not match the execution request"
            );
        }
    }

    private static String requireRequestedModel(
            String value
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "requestedModel не должен быть пустым"
            );
        }

        return value.trim();
    }
}