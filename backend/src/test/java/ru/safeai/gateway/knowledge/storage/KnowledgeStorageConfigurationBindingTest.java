package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeStorageConfigurationBindingTest {

    private static final long DEFAULT_MAX_UPLOAD_BYTES =
            26_214_400L;

    private static final String DEFAULT_BUCKET =
            "safeai-knowledge";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            KnowledgeStorageConfiguration.class
                    );

    @Test
    void bindsCompleteS3Configuration() {
        contextRunner
                .withPropertyValues(
                        "safeai.knowledge.storage.type=s3",
                        "safeai.knowledge.storage.local-root=./tmp/knowledge",
                        "safeai.knowledge.storage.max-upload-bytes=123456",
                        "safeai.knowledge.storage.endpoint=http://localhost:9000",
                        "safeai.knowledge.storage.access-key=safeai",
                        "safeai.knowledge.storage.secret-key=secret-value",
                        "safeai.knowledge.storage.bucket=safeai-knowledge"
                )
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed();

                            KnowledgeStorageProperties properties =
                                    context.getBean(
                                            KnowledgeStorageProperties.class
                                    );

                            assertThat(properties.type())
                                    .isEqualTo(
                                            KnowledgeStorageType.S3
                                    );

                            assertThat(
                                    properties.localRoot()
                                            .normalize()
                            ).isEqualTo(
                                    Path.of("tmp/knowledge")
                            );

                            assertThat(properties.maxUploadBytes())
                                    .isEqualTo(123_456L);

                            assertThat(properties.endpoint())
                                    .isEqualTo(
                                            "http://localhost:9000"
                                    );

                            assertThat(properties.accessKey())
                                    .isEqualTo("safeai");

                            assertThat(properties.secretKey())
                                    .isEqualTo(
                                            "secret-value"
                                    );

                            assertThat(properties.bucket())
                                    .isEqualTo(
                                            DEFAULT_BUCKET
                                    );
                        }
                );
    }

    @Test
    void missingS3SecretFailsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "safeai.knowledge.storage.type=s3",
                        "safeai.knowledge.storage.endpoint=http://localhost:9000",
                        "safeai.knowledge.storage.access-key=safeai",
                        "safeai.knowledge.storage.bucket=safeai-knowledge"
                )
                .run(
                        context -> {
                            assertThat(context)
                                    .hasFailed();

                            assertThat(
                                    context.getStartupFailure()
                            )
                                    .hasRootCauseInstanceOf(
                                            IllegalStateException.class
                                    )
                                    .hasRootCauseMessage(
                                            "safeai.knowledge.storage.secret-key должен быть задан для storage.type=s3"
                                    );
                        }
                );
    }

    @Test
    void emptyConfigurationBindsLocalDefaults() {
        contextRunner.run(
                context -> {
                    assertThat(context)
                            .hasNotFailed();

                    KnowledgeStorageProperties properties =
                            context.getBean(
                                    KnowledgeStorageProperties.class
                            );

                    assertThat(properties.type())
                            .isEqualTo(
                                    KnowledgeStorageType.LOCAL
                            );

                    assertThat(
                            properties.localRoot()
                                    .normalize()
                    ).isEqualTo(
                            Path.of("var/knowledge-objects")
                    );

                    assertThat(properties.bucket())
                            .isEqualTo(
                                    DEFAULT_BUCKET
                            );

                    assertThat(properties.maxUploadBytes())
                            .isEqualTo(
                                    DEFAULT_MAX_UPLOAD_BYTES
                            );

                    assertThat(properties.endpoint())
                            .isNull();

                    assertThat(properties.accessKey())
                            .isNull();

                    assertThat(properties.secretKey())
                            .isNull();
                }
        );
    }

    @Test
    void blankOptionalValuesAreNormalizedToDefaultsForLocalStorage() {
        contextRunner
                .withPropertyValues(
                        "safeai.knowledge.storage.type=local",
                        "safeai.knowledge.storage.endpoint=   ",
                        "safeai.knowledge.storage.access-key=   ",
                        "safeai.knowledge.storage.secret-key=   ",
                        "safeai.knowledge.storage.bucket=   "
                )
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed();

                            KnowledgeStorageProperties properties =
                                    context.getBean(
                                            KnowledgeStorageProperties.class
                                    );

                            assertThat(properties.type())
                                    .isEqualTo(
                                            KnowledgeStorageType.LOCAL
                                    );

                            assertThat(properties.endpoint())
                                    .isNull();

                            assertThat(properties.accessKey())
                                    .isNull();

                            assertThat(properties.secretKey())
                                    .isNull();

                            assertThat(properties.bucket())
                                    .isEqualTo(
                                            DEFAULT_BUCKET
                                    );
                        }
                );
    }
}