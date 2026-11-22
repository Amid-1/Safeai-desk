package ru.safeai.gateway.knowledge.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.safeai.gateway.ai.config.ProviderSecretFile;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

@Tag("unit")
class KnowledgeProviderSecretConfigurationValidatorTest {

    private static final String EMBEDDING_KEY =
            "sk-fixture-embedding-0123456789";

    private static final String OCR_KEY =
            "sk-fixture-ocr-0123456789";

    private static final Path EMBEDDING_SECRET =
            Path.of("/run/secrets/OPENAI_EMBEDDING_API_KEY");

    private static final Path OCR_SECRET =
            Path.of("/run/secrets/KNOWLEDGE_OCR_API_KEY");

    @Test
    void validatesBothActiveProviderSecrets() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            new KnowledgeProviderSecretConfigurationValidator(
                    embedding("openai"),
                    ocr("http")
            ).validate();

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    EMBEDDING_SECRET, EMBEDDING_KEY));

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    OCR_SECRET, OCR_KEY));

            secrets.verifyNoMoreInteractions();
        }
    }

    @Test
    void validatesOnlyEmbeddingWhenOcrDisabled() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            new KnowledgeProviderSecretConfigurationValidator(
                    embedding("openai"),
                    ocr("disabled")
            ).validate();

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    EMBEDDING_SECRET, EMBEDDING_KEY));

            secrets.verifyNoMoreInteractions();
        }
    }

    @Test
    void validatesOnlyOcrWhenEmbeddingUsesHashing() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            new KnowledgeProviderSecretConfigurationValidator(
                    embedding("hashing"),
                    ocr("http")
            ).validate();

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    OCR_SECRET, OCR_KEY));

            secrets.verifyNoMoreInteractions();
        }
    }

    @Test
    void inactiveExternalProvidersDoNotAccessSecretFiles() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            new KnowledgeProviderSecretConfigurationValidator(
                    embedding("hashing"),
                    ocr("disabled")
            ).validate();

            secrets.verifyNoInteractions();
        }
    }

    @Test
    void invalidEmbeddingSecretFailsClosed() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            var failure = new IllegalStateException(
                    "Invalid embedding secret");

            secrets.when(() -> ProviderSecretFile.requireMatches(
                    EMBEDDING_SECRET, EMBEDDING_KEY))
                    .thenThrow(failure);

            var validator =
                    new KnowledgeProviderSecretConfigurationValidator(
                            embedding("openai"),
                            ocr("http")
                    );

            assertThatThrownBy(validator::validate)
                    .isSameAs(failure);

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    EMBEDDING_SECRET, EMBEDDING_KEY));

            secrets.verifyNoMoreInteractions();
        }
    }

    @Test
    void invalidOcrSecretFailsClosed() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            var failure = new IllegalStateException(
                    "Invalid OCR secret");

            secrets.when(() -> ProviderSecretFile.requireMatches(
                    OCR_SECRET, OCR_KEY))
                    .thenThrow(failure);

            var validator =
                    new KnowledgeProviderSecretConfigurationValidator(
                            embedding("openai"),
                            ocr("http")
                    );

            assertThatThrownBy(validator::validate)
                    .isSameAs(failure);

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    EMBEDDING_SECRET, EMBEDDING_KEY));

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    OCR_SECRET, OCR_KEY));

            secrets.verifyNoMoreInteractions();
        }
    }

    @Test
    void invalidSecretPreventsProductionContextStartup() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            secrets.when(() -> ProviderSecretFile.requireMatches(
                    EMBEDDING_SECRET, EMBEDDING_KEY))
                    .thenThrow(new IllegalStateException(
                            "Invalid embedding secret"));

            contextRunner("prod")
                    .run(context -> {
                        assertThat(context).hasFailed();

                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(
                                        IllegalStateException.class)
                                .hasRootCauseMessage(
                                        "Invalid embedding secret");
                    });
        }
    }

    @Test
    void productionContextValidatesSecretsDuringStartup() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            contextRunner("prod")
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        assertThat(context).hasSingleBean(
                                KnowledgeProviderSecretConfigurationValidator.class);
                    });

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    EMBEDDING_SECRET, EMBEDDING_KEY));

            secrets.verify(() -> ProviderSecretFile.requireMatches(
                    OCR_SECRET, OCR_KEY));

            secrets.verifyNoMoreInteractions();
        }
    }

    @Test
    void localContextDoesNotActivateProductionValidator() {
        try (var secrets = mockStatic(ProviderSecretFile.class)) {
            contextRunner("local")
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        assertThat(context).doesNotHaveBean(
                                KnowledgeProviderSecretConfigurationValidator.class);
                    });

            secrets.verifyNoInteractions();
        }
    }

    private static ApplicationContextRunner contextRunner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(context ->
                        context.getEnvironment().setActiveProfiles(profile))
                .withBean(
                        KnowledgeEmbeddingProperties.class,
                        () -> embedding("openai")
                )
                .withBean(
                        KnowledgeOcrProperties.class,
                        () -> ocr("http")
                )
                .withUserConfiguration(
                        KnowledgeProviderSecretConfigurationValidator.class
                );
    }

    private static KnowledgeEmbeddingProperties embedding(String provider) {
        return new KnowledgeEmbeddingProperties(
                provider,
                null,
                EMBEDDING_KEY,
                null,
                384,
                null,
                null,
                null,
                null
        );
    }

    private static KnowledgeOcrProperties ocr(String provider) {
        return new KnowledgeOcrProperties(
                provider,
                "https://ocr.example.com/v1",
                OCR_KEY,
                List.of("ocr.example.com"),
                null,
                null,
                null,
                null,
                null
        );
    }
}
