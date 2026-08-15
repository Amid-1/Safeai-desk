package ru.safeai.gateway.knowledge.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KnowledgeStorageProperties.class)
public class KnowledgeStorageConfiguration {
}
