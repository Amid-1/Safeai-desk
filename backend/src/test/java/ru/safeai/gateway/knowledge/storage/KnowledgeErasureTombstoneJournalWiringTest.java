// backend/src/test/java/ru/safeai/gateway/knowledge/storage/KnowledgeErasureTombstoneJournalWiringTest.java
package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет конфигурацию внешнего журнала удаления на Spring Boot 4 и Jackson 3.
 *
 * <p>Тест не запускает PostgreSQL, Docker или S3: ошибки конфигурации
 * должны обнаруживаться до появления внешних сетевых соединений.</p>
 *
 * <p>{@link ApplicationContextRunner} не запускает полноценную автоконфигурацию
 * Spring Boot. Поэтому для обработки строковых значений {@code @Value},
 * включая ISO-8601 {@link Duration}, явно устанавливается тот же
 * {@link ApplicationConversionService}, который используется приложением.
 * Это не замена и не обход проверки срока удержания в production-классе.</p>
 */
class KnowledgeErasureTombstoneJournalWiringTest {

    @Configuration(proxyBeanMethods = false)
    @Import(KnowledgeErasureTombstoneJournal.class)
    static class JournalConfiguration {
    }

    private final ApplicationContextRunner runner = runnerWithStorage(
            new KnowledgeStorageProperties(
                    KnowledgeStorageType.LOCAL, null, null,
                    null, null, null, null));

    /**
     * Создаёт минимальный контекст с настоящими конвертерами Spring Boot.
     * Специально не регистрирует Jackson 2 ObjectMapper и не подменяет журнал.
     */
    private static ApplicationContextRunner runnerWithStorage(KnowledgeStorageProperties storage) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(JournalConfiguration.class)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean(KnowledgeStorageProperties.class, () -> storage);
    }

    @Test
    void disabledJournalStartsWithJackson3AndRemainsFailClosed() {
        runner.withPropertyValues("safeai.knowledge.erasure-journal.enabled=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBean(JsonMapper.class)).isNotNull();
                    KnowledgeErasureTombstoneJournal journal =
                            context.getBean(KnowledgeErasureTombstoneJournal.class);
                    assertThat(journal).isNotNull();

                    // Выключенный журнал не подтверждает намерение удалить документ.
                    assertThatThrownBy(() -> journal.publishDocumentIntent(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID(), UUID.randomUUID(), 0L))
                            .isExactlyInstanceOf(IOException.class)
                            .hasMessageContaining("disabled");
                });
    }

    @Test
    void disabledJournalRejectsEveryPublicationType() {
        runner.withPropertyValues("safeai.knowledge.erasure-journal.enabled=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    KnowledgeErasureTombstoneJournal journal =
                            context.getBean(KnowledgeErasureTombstoneJournal.class);
                    UUID operation = UUID.randomUUID();
                    UUID organization = UUID.randomUUID();
                    UUID base = UUID.randomUUID();
                    UUID document = UUID.randomUUID();

                    // Ни один путь публикации не может сообщить об успешном удалении.
                    assertThatThrownBy(() -> journal.publishObjectPurgeIntent(
                            operation, organization, base, document, "org/base/document"))
                            .isExactlyInstanceOf(IOException.class)
                            .hasMessageContaining("disabled");
                    assertThatThrownBy(() -> journal.publishObjectPurged(
                            operation, organization, base, document, "org/base/document"))
                            .isExactlyInstanceOf(IOException.class)
                            .hasMessageContaining("disabled");
                    assertThatThrownBy(() -> journal.publishDocumentCommitted(
                            operation, organization, base, document))
                            .isExactlyInstanceOf(IOException.class)
                            .hasMessageContaining("disabled");
                    assertThatThrownBy(() -> journal.publishAttention(
                            operation, organization, base, document, "S3_UNAVAILABLE"))
                            .isExactlyInstanceOf(IOException.class)
                            .hasMessageContaining("disabled");
                    assertThatThrownBy(() -> journal.publishBaseCommitted(
                            operation, organization, base, UUID.randomUUID(), 0L))
                            .isExactlyInstanceOf(IOException.class)
                            .hasMessageContaining("disabled");
                });
    }

    @Test
    void explicitIso8601RetentionIsConvertedBySpringBoot() {
        runner.withPropertyValues(
                        "safeai.knowledge.erasure-journal.enabled=false",
                        "safeai.knowledge.erasure-journal.min-retention=P30D")
                .run(context -> {
                    // Не принимаем успешный тест лишь из-за выключенного feature flag:
                    // конструктор всегда валидирует минимальное время удержания.
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBean(KnowledgeErasureTombstoneJournal.class))
                            .isNotNull();
                    assertThat(ApplicationConversionService.getSharedInstance()
                            .convert("P30D", Duration.class))
                            .isEqualTo(Duration.ofDays(30));
                });
    }

    @Test
    void invalidRetentionIsRejectedEvenWhenJournalIsDisabled() {
        for (String retention : new String[]{"P0D", "P36501D"}) {
            runner.withPropertyValues(
                            "safeai.knowledge.erasure-journal.enabled=false",
                            "safeai.knowledge.erasure-journal.min-retention=" + retention)
                    .run(context -> {
                        // Неверную конфигурацию нельзя скрыть выключенным журналом.
                        assertThat(context.getStartupFailure()).isNotNull();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                                .hasRootCauseMessage(
                                        "Erasure journal minimum retention must be 1..36500 days");
                    });
        }
    }

    @Test
    void enabledJournalRequiresVerifiedObjectLockConfiguration() {
        runner.withPropertyValues(
                        "safeai.knowledge.erasure-journal.enabled=true",
                        "safeai.knowledge.erasure-journal.object-lock-required=false")
                .run(context -> {
                    // Отказ должен происходить из-за отсутствия Object Lock,
                    // а не из-за ошибки преобразования Duration или JSON.
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "External erasure journal requires an independently verified "
                                            + "Object Lock Compliance bucket");
                });
    }

    @Test
    void enabledJournalDoesNotAcceptLocalStorageEvenWithObjectLockFlag() {
        runner.withPropertyValues(
                        "safeai.knowledge.erasure-journal.enabled=true",
                        "safeai.knowledge.erasure-journal.object-lock-required=true")
                .run(context -> {
                    // Одного feature flag недостаточно: журнал требует настоящее S3.
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "Erasure journal requires S3 document storage configuration");
                });
    }

    @Test
    void enabledJournalRejectsSameBucketAsDocumentStorageBeforeNetworkAccess() {
        KnowledgeStorageProperties storage = new KnowledgeStorageProperties(
                KnowledgeStorageType.S3, null, null,
                "http://127.0.0.1:9000", "integration-access", "integration-secret",
                "safeai-documents-test");

        runnerWithStorage(storage).withPropertyValues(
                        "safeai.knowledge.erasure-journal.enabled=true",
                        "safeai.knowledge.erasure-journal.object-lock-required=true",
                        "safeai.knowledge.erasure-journal.bucket=safeai-documents-test")
                .run(context -> {
                    // Журнал доказательств не должен находиться в том же бакете,
                    // что и удаляемые файлы. Проверка выполняется без соединения с S3.
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "Erasure journal must use a distinct, non-empty S3 bucket");
                });
    }
}
