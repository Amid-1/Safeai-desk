package ru.safeai.gateway.knowledge.storage.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the reconciliation properties even while the worker is disabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeStorageReconciliationProperties.class)
public class KnowledgeStorageReconciliationConfiguration {
}
