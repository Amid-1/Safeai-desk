package ru.safeai.gateway.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.safeai.gateway.common.security.ProductionSecurityInvariantValidator;

/**
 * Размер scheduler pool задаётся через {@code spring.task.scheduling.*}.
 *
 * <p>При нескольких backend instances scheduled job должен либо:</p>
 * <ul>
 *     <li>использовать distributed lock, если выполнение должно быть singleton;</li>
 *     <li>быть спроектирован как concurrency-safe distributed worker.</li>
 * </ul>
 *
 * <p>Production configuration is fail-fast checked by
 * {@link ProductionSecurityInvariantValidator}: the scheduler pool must be
 * declared explicitly and contain at least two threads so one slow maintenance
 * job cannot starve every other scheduled task.</p>
 *
 * <p>Например, refresh-token cleanup относится ко второму варианту:
 * workers безопасно конкурируют за batches через FOR UPDATE SKIP LOCKED,
 * а каждый batch фиксируется отдельной REQUIRES_NEW transaction.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
}
