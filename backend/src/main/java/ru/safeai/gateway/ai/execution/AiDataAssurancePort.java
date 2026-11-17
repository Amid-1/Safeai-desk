package ru.safeai.gateway.ai.execution;

/** Revalidate external data-handling proof before physical I/O. */
public interface AiDataAssurancePort {
    void require(AiExecutionRequest request, boolean started);
}
