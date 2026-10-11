package ru.safeai.gateway.ai.execution;

import java.util.UUID;

/** Minimal capability identity seen by the physical AI boundary. */
public interface AiExecutionIdentity {

    UUID decisionId();

    UUID chatTurnId();
}
