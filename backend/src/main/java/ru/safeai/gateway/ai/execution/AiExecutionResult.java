package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatResponse;
import java.util.UUID;

public record AiExecutionResult(UUID executionPlanId, AiChatResponse response) { }
