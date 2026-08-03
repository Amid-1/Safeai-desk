package ru.safeai.gateway.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.chat")
public record ChatProperties(
        Integer detailsMessageLimit,
        Integer historyTurnLimit,
        Integer maxChatPageSize,
        Integer maxMessagePageSize,
        Integer maxMessageChars,
        Duration processingLease,
        Integer leaseRenewalThreads,
        Integer maxActiveLeaseWatchdogs
) {

    private static final int MIN_POSITIVE_VALUE = 1;

    private static final int DEFAULT_DETAILS_MESSAGE_LIMIT = 50;
    private static final int MAX_DETAILS_MESSAGE_LIMIT = 200;

    private static final int DEFAULT_HISTORY_TURN_LIMIT = 50;
    private static final int MAX_HISTORY_TURN_LIMIT = 200;

    private static final int DEFAULT_CHAT_PAGE_SIZE = 100;
    private static final int MAX_CHAT_PAGE_SIZE = 100;

    private static final int DEFAULT_MESSAGE_PAGE_SIZE = 100;
    private static final int MAX_MESSAGE_PAGE_SIZE = 100;

    private static final int DEFAULT_MAX_MESSAGE_CHARS = 16_000;
    private static final int MAX_MESSAGE_CHARS = 100_000;

    private static final int DEFAULT_LEASE_RENEWAL_THREADS = 4;
    private static final int MAX_LEASE_RENEWAL_THREADS = 32;

    private static final int DEFAULT_MAX_ACTIVE_LEASE_WATCHDOGS = 1_000;
    private static final int MAX_ACTIVE_LEASE_WATCHDOGS = 100_000;

    private static final Duration DEFAULT_PROCESSING_LEASE =
            Duration.ofMinutes(3);

    private static final Duration MIN_PROCESSING_LEASE =
            Duration.ofSeconds(30);

    private static final Duration MAX_PROCESSING_LEASE =
            Duration.ofMinutes(30);

    public ChatProperties {
        detailsMessageLimit = boundedPositive(
                detailsMessageLimit,
                DEFAULT_DETAILS_MESSAGE_LIMIT,
                MAX_DETAILS_MESSAGE_LIMIT,
                "details-message-limit"
        );

        historyTurnLimit = boundedPositive(
                historyTurnLimit,
                DEFAULT_HISTORY_TURN_LIMIT,
                MAX_HISTORY_TURN_LIMIT,
                "history-turn-limit"
        );

        maxChatPageSize = boundedPositive(
                maxChatPageSize,
                DEFAULT_CHAT_PAGE_SIZE,
                MAX_CHAT_PAGE_SIZE,
                "max-chat-page-size"
        );

        maxMessagePageSize = boundedPositive(
                maxMessagePageSize,
                DEFAULT_MESSAGE_PAGE_SIZE,
                MAX_MESSAGE_PAGE_SIZE,
                "max-message-page-size"
        );

        maxMessageChars = boundedPositive(
                maxMessageChars,
                DEFAULT_MAX_MESSAGE_CHARS,
                MAX_MESSAGE_CHARS,
                "max-message-chars"
        );

        processingLease = processingLease == null
                ? DEFAULT_PROCESSING_LEASE
                : processingLease;

        if (processingLease.compareTo(MIN_PROCESSING_LEASE) < 0
                || processingLease.compareTo(MAX_PROCESSING_LEASE) > 0) {
            throw new IllegalStateException(
                    "safeai.chat.processing-lease должен быть "
                            + "в диапазоне 30s–30m"
            );
        }

        leaseRenewalThreads = boundedPositive(
                leaseRenewalThreads,
                DEFAULT_LEASE_RENEWAL_THREADS,
                MAX_LEASE_RENEWAL_THREADS,
                "lease-renewal-threads"
        );

        maxActiveLeaseWatchdogs = boundedPositive(
                maxActiveLeaseWatchdogs,
                DEFAULT_MAX_ACTIVE_LEASE_WATCHDOGS,
                MAX_ACTIVE_LEASE_WATCHDOGS,
                "max-active-lease-watchdogs"
        );
    }

    public Duration leaseRenewalInterval() {
        long intervalMillis = Math.max(
                1_000L,
                processingLease.toMillis() / 3L
        );

        return Duration.ofMillis(intervalMillis);
    }

    private static int boundedPositive(
            Integer value,
            int defaultValue,
            int maxValue,
            String propertyName
    ) {
        int resolved = value == null
                ? defaultValue
                : value;

        if (resolved < MIN_POSITIVE_VALUE
                || resolved > maxValue) {
            throw new IllegalStateException(
                    "safeai.chat."
                            + propertyName
                            + " должен быть в диапазоне "
                            + MIN_POSITIVE_VALUE
                            + "–"
                            + maxValue
            );
        }

        return resolved;
    }
}