package ru.safeai.gateway.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Размер scheduler pool задаётся через spring.task.scheduling.*.
 *
 * <p>При нескольких backend instances scheduled job должен либо:</p>
 * <ul>
 *     <li>использовать distributed lock, если выполнение должно быть singleton;</li>
 *     <li>быть спроектирован как concurrency-safe distributed worker.</li>
 * </ul>
 *
 * <p>Например, refresh-token cleanup относится ко второму варианту:
 * workers безопасно конкурируют за batches через FOR UPDATE SKIP LOCKED,
 * а каждый batch фиксируется отдельной REQUIRES_NEW transaction.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
}
