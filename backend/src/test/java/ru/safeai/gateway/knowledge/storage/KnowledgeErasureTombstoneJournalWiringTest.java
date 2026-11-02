package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет создание V85-журнала без PostgreSQL, Docker и внешнего S3.
 *
 * <p>Это регрессионная проверка совместимости Jackson 3 с Spring Boot 4:
 * для запуска приложения не должен требоваться Jackson 2 ObjectMapper.</p>
 */
class KnowledgeErasureTombstoneJournalWiringTest {

    @Configuration(proxyBeanMethods = false)
    @Import(KnowledgeErasureTombstoneJournal.class)
    static class JournalConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JournalConfiguration.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(KnowledgeStorageProperties.class,
                    () -> new KnowledgeStorageProperties(
                            KnowledgeStorageType.LOCAL, null, null,
                            null, null, null, null));

    @Test
    void disabledJournalStartsWithJackson3AndRemainsFailClosed() {
        runner.withPropertyValues("safeai.knowledge.erasure-journal.enabled=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    KnowledgeErasureTombstoneJournal journal =
                            context.getBean(KnowledgeErasureTombstoneJournal.class);
                    assertThat(journal).isNotNull();

                    // Выключенный журнал никогда не подтверждает физическое удаление.
                    UUID operationId = UUID.randomUUID();
                    assertThatThrownBy(() -> journal.publishDocumentIntent(
                            operationId, UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID(), UUID.randomUUID(), 0L))
                            .isInstanceOf(IOException.class)
                            .hasMessageContaining("disabled");
                });
    }

    @Test
    void enabledJournalRequiresVerifiedObjectLockConfiguration() {
        runner.withPropertyValues(
                        "safeai.knowledge.erasure-journal.enabled=true",
                        "safeai.knowledge.erasure-journal.object-lock-required=false")
                .run(context -> {
                    // Нельзя запустить включённый журнал без обязательной
                    // конфигурации защищённого отдельного бакета.
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }
}
