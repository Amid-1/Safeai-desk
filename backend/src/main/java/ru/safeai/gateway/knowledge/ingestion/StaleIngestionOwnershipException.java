package ru.safeai.gateway.knowledge.ingestion;

public class StaleIngestionOwnershipException extends RuntimeException {

    public StaleIngestionOwnershipException() {
        super("Ingestion ownership was lost");
    }
}
