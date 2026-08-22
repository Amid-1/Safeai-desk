package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeStoragePropertiesTest {

    private static final Path DEFAULT_LOCAL_ROOT =
            Path.of("./var/knowledge-objects");

    private static final long DEFAULT_MAX_UPLOAD_BYTES =
            25L * 1024L * 1024L;

    private static final long MIN_MAX_UPLOAD_BYTES =
            1024L * 1024L;

    private static final long MAX_MAX_UPLOAD_BYTES =
            100L * 1024L * 1024L;

    private static final long EXPLICIT_UPLOAD_LIMIT =
            2L * 1024L * 1024L;

    private static final String DEFAULT_BUCKET =
            "safeai-knowledge";

    @Test
    void constructor_usesSafeLocalDefaults() {
        KnowledgeStorageProperties properties =
                new KnowledgeStorageProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.type())
                .isEqualTo(
                        KnowledgeStorageType.LOCAL
                );

        assertThat(properties.localRoot())
                .isEqualTo(
                        DEFAULT_LOCAL_ROOT
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

        assertThat(properties.bucket())
                .isEqualTo(
                        DEFAULT_BUCKET
                );
    }

    @Test
    void constructor_keepsExplicitLocalConfiguration() {
        Path localRoot =
                Path.of(
                        "./target/test-knowledge"
                );

        KnowledgeStorageProperties properties =
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        localRoot,
                        EXPLICIT_UPLOAD_LIMIT,
                        null,
                        null,
                        null,
                        "custom-bucket"
                );

        assertThat(properties.type())
                .isEqualTo(
                        KnowledgeStorageType.LOCAL
                );

        assertThat(properties.localRoot())
                .isEqualTo(
                        localRoot.normalize()
                );

        assertThat(properties.maxUploadBytes())
                .isEqualTo(
                        EXPLICIT_UPLOAD_LIMIT
                );

        assertThat(properties.bucket())
                .isEqualTo(
                        "custom-bucket"
                );
    }

    @Test
    void constructor_normalizesS3StringValues() {
        KnowledgeStorageProperties properties =
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "  http://localhost:9000  ",
                        "  access-key  ",
                        "  secret-value  ",
                        "  safeai-knowledge  "
                );

        assertThat(properties.type())
                .isEqualTo(
                        KnowledgeStorageType.S3
                );

        assertThat(properties.endpoint())
                .isEqualTo(
                        "http://localhost:9000"
                );

        assertThat(properties.accessKey())
                .isEqualTo(
                        "access-key"
                );

        assertThat(properties.secretKey())
                .isEqualTo(
                        "secret-value"
                );

        assertThat(properties.bucket())
                .isEqualTo(
                        "safeai-knowledge"
                );

        assertThat(properties.maxUploadBytes())
                .isEqualTo(
                        EXPLICIT_UPLOAD_LIMIT
                );
    }

    @Test
    void constructor_replacesBlankBucketWithDefault() {
        KnowledgeStorageProperties properties =
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        null,
                        null,
                        null,
                        "   "
                );

        assertThat(properties.bucket())
                .isEqualTo(
                        DEFAULT_BUCKET
                );
    }

    @Test
    void constructor_rejectsZeroUploadLimit() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        0L,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "safeai.knowledge.storage.max-upload-bytes"
                );
    }

    @Test
    void constructor_rejectsNegativeUploadLimit() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        -1L,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "safeai.knowledge.storage.max-upload-bytes"
                );
    }

    @Test
    void constructor_rejectsUploadLimitBelowMinimum() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        MIN_MAX_UPLOAD_BYTES - 1L,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "safeai.knowledge.storage.max-upload-bytes"
                );
    }

    @Test
    void constructor_acceptsMinimumUploadLimit() {
        KnowledgeStorageProperties properties =
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        MIN_MAX_UPLOAD_BYTES,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.maxUploadBytes())
                .isEqualTo(
                        MIN_MAX_UPLOAD_BYTES
                );
    }

    @Test
    void constructor_acceptsMaximumUploadLimit() {
        KnowledgeStorageProperties properties =
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        MAX_MAX_UPLOAD_BYTES,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.maxUploadBytes())
                .isEqualTo(
                        MAX_MAX_UPLOAD_BYTES
                );
    }

    @Test
    void constructor_rejectsUploadLimitAboveMaximum() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        MAX_MAX_UPLOAD_BYTES + 1L,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "safeai.knowledge.storage.max-upload-bytes"
                );
    }

    @Test
    void constructor_rejectsMissingS3Endpoint() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        null,
                        "access",
                        "secret",
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "safeai.knowledge.storage.endpoint"
                );
    }

    @Test
    void constructor_rejectsBlankS3Endpoint() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "   ",
                        "access",
                        "secret",
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "endpoint"
                );
    }

    @Test
    void constructor_rejectsMissingS3AccessKey() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "http://localhost:9000",
                        null,
                        "secret",
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "access-key"
                );
    }

    @Test
    void constructor_rejectsBlankS3AccessKey() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "http://localhost:9000",
                        "   ",
                        "secret",
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "access-key"
                );
    }

    @Test
    void constructor_rejectsMissingS3SecretKey() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "http://localhost:9000",
                        "access",
                        null,
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "secret-key"
                );
    }

    @Test
    void constructor_rejectsBlankS3SecretKey() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "http://localhost:9000",
                        "access",
                        "\t  ",
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "secret-key"
                );
    }

    @Test
    void constructor_rejectsInvalidS3EndpointScheme() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "ftp://localhost:9000",
                        "access",
                        "secret",
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "endpoint"
                );
    }

    @Test
    void constructor_rejectsS3EndpointWithCredentials() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "https://user:password@example.com",
                        "access",
                        "secret",
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "endpoint"
                );
    }

    @Test
    void constructor_rejectsS3EndpointWithQuery() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.S3,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        "https://example.com?token=value",
                        "access",
                        "secret",
                        DEFAULT_BUCKET
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "endpoint"
                );
    }

    @Test
    void constructor_rejectsInvalidBucket() {
        assertThatThrownBy(
                () -> new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        null,
                        null,
                        null,
                        "Invalid_Bucket"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "bucket"
                );
    }

    @Test
    void localStorage_doesNotRequireS3Credentials() {
        KnowledgeStorageProperties properties =
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        null,
                        EXPLICIT_UPLOAD_LIMIT,
                        null,
                        null,
                        null,
                        null
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
    }
}