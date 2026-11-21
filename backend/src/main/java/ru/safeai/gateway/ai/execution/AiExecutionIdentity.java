package ru.safeai.gateway.ai.execution;

import java.util.UUID;

/** Minimal capability identity seen by the physical AI boundary. */
public interface AiExecutionIdentity {

    UUID decisionId();

    UUID chatTurnId();

    /** True only for an execution bound to a Knowledge base. */
    default boolean usesKnowledge() {
        return false;
    }

    /** Knowledge base identity, when this execution uses Knowledge. */
    default UUID knowledgeBaseId() {
        return null;
    }

    /** Tenant identity, when the concrete execution capability carries it. */
    default UUID organizationId() {
        return null;
    }
}
