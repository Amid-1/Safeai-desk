package ru.safeai.gateway.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Размер scheduler pool задаётся через spring.task.scheduling.*.
 * Для нескольких backend instances scheduled jobs обязаны иметь distributed lock.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
}
