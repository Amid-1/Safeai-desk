
package ru.safeai.gateway.knowledge.storage;

import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.SetBucketVersioningArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.minio.messages.VersioningConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test against a real S3-compatible server with bucket versioning.
 * Verifies permanent deletion of all object versions and delete markers, not
 * merely creation of a new delete marker.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3VerifiedKnowledgeObjectPurgeIntegrationTest {

    private static final String ACCESS_KEY = "safeai";
    private static final String SECRET_KEY = "safeai-local-change-me";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("pgsty/silo:RELEASE.2026-10-18T00-00-00Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live")
                    .forPort(9000)
                    .forStatusCode(200));

    @Test
    void removesAllVersionsAndMarkersWithoutTouchingNeighboringPrefix() throws Exception {
        // The container serves plain HTTP on port 9000. TLS is not configured.
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        String bucket = "safeai-purge-" + UUID.randomUUID().toString().replace("-", "");

        try (MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build()) {

            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            client.setBucketVersioning(SetBucketVersioningArgs.builder()
                    .bucket(bucket)
                    .config(new VersioningConfiguration(
                            VersioningConfiguration.Status.ENABLED, false))
                    .build());

            // Do not run a misleading purge test against an unversioned bucket.
            assertThat(client.getBucketVersioning(GetBucketVersioningArgs.builder()
                    .bucket(bucket)
                    .build()).status())
                    .isEqualTo(VersioningConfiguration.Status.ENABLED);

            S3ObjectStorage storage = new S3ObjectStorage(new KnowledgeStorageProperties(
                    KnowledgeStorageType.S3,
                    null,
                    26_214_400L,
                    endpoint,
                    ACCESS_KEY,
                    SECRET_KEY,
                    bucket));

            try {
                String key = "org/base/doc/" + UUID.randomUUID();
                String neighbor = key + "-other";

                storage.put(key, bytes("one"));
                storage.put(key, bytes("two"));
                storage.delete(key); // Normal DELETE creates a marker, not a physical purge.

                // Two neighbor versions also catch accidental prefix-wide deletion.
                storage.put(neighbor, bytes("neighbor-v1"));
                storage.put(neighbor, bytes("neighbor-v2"));

                List<Item> versionsBefore = listExactVersions(client, bucket, key);
                assertThat(versionsBefore).hasSize(3);
                assertThat(versionsBefore.stream().filter(Item::isDeleteMarker).count())
                        .isEqualTo(1L);
                assertThat(versionsBefore.stream().filter(item -> !item.isDeleteMarker()).count())
                        .isEqualTo(2L);
                assertThat(versionsBefore)
                        .allMatch(item -> item.versionId() != null && !item.versionId().isBlank());

                // Historical content must really exist even though the current key is masked.
                String historicalVersionId = versionsBefore.stream()
                        .filter(item -> !item.isDeleteMarker())
                        .findFirst()
                        .orElseThrow()
                        .versionId();
                assertThat(readVersion(client, bucket, key, historicalVersionId))
                        .isIn("one", "two");

                List<String> neighborVersionIds = listExactVersions(client, bucket, neighbor)
                        .stream()
                        .map(Item::versionId)
                        .toList();
                assertThat(neighborVersionIds).hasSize(2);
                assertThat(readLatest(client, bucket, neighbor)).isEqualTo("neighbor-v2");

                storage.purgeAllVersionsVerified(key);

                // Check the complete version listing, including delete markers.
                assertThat(listExactVersions(client, bucket, key)).isEmpty();
                assertThat(listExactVersions(client, bucket, neighbor)
                        .stream().map(Item::versionId).toList())
                        .containsExactlyInAnyOrderElementsOf(neighborVersionIds);
                assertThat(readLatest(client, bucket, neighbor)).isEqualTo("neighbor-v2");

                // A previously readable historical version must no longer be retrievable.
                assertThatThrownBy(() -> readVersion(client, bucket, key, historicalVersionId))
                        .isInstanceOf(ErrorResponseException.class)
                        .satisfies(error -> assertThat(
                                ((ErrorResponseException) error).errorResponse().code())
                                .isIn("NoSuchVersion", "NoSuchKey", "NotFound"));

                // Retry after confirmed absence must not recreate markers or touch neighbors.
                storage.purgeAllVersionsVerified(key);
                assertThat(listExactVersions(client, bucket, key)).isEmpty();
                assertThat(listExactVersions(client, bucket, neighbor)
                        .stream().map(Item::versionId).toList())
                        .containsExactlyInAnyOrderElementsOf(neighborVersionIds);
            } finally {
                storage.closeClient();
            }
        }
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Item> listExactVersions(MinioClient client, String bucket, String key)
            throws Exception {
        List<Item> exactVersions = new ArrayList<>();
        for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(key)
                .recursive(true)
                .includeVersions(true)
                .build())) {
            Item item = result.get(); // Also propagates failures of lazy pagination.
            if (key.equals(item.objectName())) {
                exactVersions.add(item);
            }
        }
        return exactVersions;
    }

    private static String readVersion(MinioClient client, String bucket,
                                      String key, String versionId) throws Exception {
        try (var response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .versionId(versionId)
                .build())) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readLatest(MinioClient client, String bucket, String key)
            throws Exception {
        try (var response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
