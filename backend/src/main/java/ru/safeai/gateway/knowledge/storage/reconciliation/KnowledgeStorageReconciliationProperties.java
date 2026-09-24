package ru.safeai.gateway.knowledge.storage.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Opt-in reconciliation settings. Keeping a typed configuration contract makes
 * application YAML discoverable by Spring Boot configuration metadata.
 * The scheduler remains off until it is explicitly enabled.
 */
@ConfigurationProperties(prefix = "safeai.knowledge.storage-reconciliation")
public record KnowledgeStorageReconciliationProperties(
        Boolean enabled,
        Duration pollDelay
) {
    public KnowledgeStorageReconciliationProperties {
        enabled = enabled != null && enabled;
        pollDelay = pollDelay == null ? Duration.ofMinutes(5) : pollDelay;
        if (pollDelay.isNegative() || pollDelay.isZero()) {
            throw new IllegalStateException(
                    "safeai.knowledge.storage-reconciliation.poll-delay must be positive"
            );
        }
    }
}
