package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;

/**
 * Provider-specific metadata-only runtime probe.
 *
 * <p>Implementations MUST NOT send prompts, chat history, RAG context, tool
 * schemas, user identifiers, organization identifiers or other customer
 * content.</p>
 */
public interface RuntimeModelHealthProbe {

    String provider();

    RuntimeModelProbeResult probe();
}
