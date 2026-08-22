package ru.safeai.gateway.knowledge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        KnowledgeIngestionProperties.class,
        KnowledgeRetrievalProperties.class,
        KnowledgeRagProperties.class,
        KnowledgeEmbeddingProperties.class,
        KnowledgeOcrProperties.class
})
public class KnowledgeProcessingConfiguration {
}
