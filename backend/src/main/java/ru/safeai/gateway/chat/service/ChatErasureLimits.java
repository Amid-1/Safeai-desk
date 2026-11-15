package ru.safeai.gateway.chat.service;

/** Shared admission/worker bounds for a single atomic PostgreSQL transaction.
 * Counts are assessed separately for message and RAG query rows. Increasing
 * these values requires a measured performance/lock-duration review. */
public final class ChatErasureLimits {
    public static final int BATCH_SIZE = 100;
    public static final int MAX_BATCHES = 100;
    public static final int MAX_ROWS_PER_KIND = BATCH_SIZE * MAX_BATCHES;
    private ChatErasureLimits() { }
}
