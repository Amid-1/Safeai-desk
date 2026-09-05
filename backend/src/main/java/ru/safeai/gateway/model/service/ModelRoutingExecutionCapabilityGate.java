package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.ModelCapability;

import java.util.Set;

/**
 * End-to-end execution capability gate.
 *
 * <p>Catalog/runtime declarations describe a model, but a capability becomes
 * executable only when AiChatRequest, provider serialization, accounting and
 * the final execution guard all support it. The current data plane is
 * text-only, therefore specialized capabilities remain fail-closed.</p>
 */
final class ModelRoutingExecutionCapabilityGate {

    private static final Set<ModelCapability> EXECUTABLE_CAPABILITIES =
            Set.of();

    private ModelRoutingExecutionCapabilityGate() {
    }

    static boolean supportsAll(
            Set<ModelCapability> required
    ) {
        return required == null
                || EXECUTABLE_CAPABILITIES.containsAll(required);
    }
}
