package ru.safeai.gateway.knowledge.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "safeai.knowledge.storage")
public record KnowledgeStorageProperties(Path localRoot, long maxUploadBytes, String endpoint, String accessKey,
                                         String secretKey, String bucket) {
    public KnowledgeStorageProperties {
        if (localRoot == null) localRoot = Path.of("./var/knowledge-objects");
        if (maxUploadBytes <= 0) maxUploadBytes = 26_214_400;
        if (bucket == null || bucket.isBlank()) bucket = "safeai-knowledge";
    }
}
