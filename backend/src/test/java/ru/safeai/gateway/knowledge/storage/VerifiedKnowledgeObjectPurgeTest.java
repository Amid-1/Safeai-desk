package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedKnowledgeObjectPurgeTest {
    @TempDir Path root;

    @Test
    void localPhysicalPurgeIsIdempotentAndCannotEscapeRoot() throws IOException {
        var store = new LocalObjectStorage(new KnowledgeStorageProperties(
            KnowledgeStorageType.LOCAL, root, 26214400L,
            null, null, null, "safeai-knowledge"));
        String key = "org/base/doc/version";
        store.put(key, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(Files.exists(root.resolve(key))).isTrue();
        store.purgeAllVersionsVerified(key);
        store.purgeAllVersionsVerified(key);
        assertThat(Files.exists(root.resolve(key))).isFalse();
        assertThatThrownBy(() -> store.purgeAllVersionsVerified("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localPurgeCannotFollowSymlinkedDirectory() throws IOException {
        var store = new LocalObjectStorage(new KnowledgeStorageProperties(
            KnowledgeStorageType.LOCAL, root, 26214400L,
            null, null, null, "safeai-knowledge"));
        Path external = Files.createTempDirectory("safeai-external-");
        try {
            Files.write(external.resolve("keep.txt"), new byte[]{42});
            Files.createDirectories(root.resolve("org"));
            Files.createSymbolicLink(root.resolve("org/link"), external);
            assertThatThrownBy(() -> store.purgeAllVersionsVerified("org/link/keep.txt"))
                .isInstanceOf(IOException.class);
            assertThat(Files.exists(external.resolve("keep.txt"))).isTrue();
        } finally {
            Files.deleteIfExists(root.resolve("org/link"));
            Files.deleteIfExists(external.resolve("keep.txt"));
            Files.deleteIfExists(external);
        }
    }

    @Test
    void unverifiedProvidersNeverSilentlyClaimErasure() {
        ObjectStorage fake = new ObjectStorage() {
            public void put(String key, java.io.InputStream stream) { }
            public StoredObject get(String key) { return null; }
            public void delete(String key) { }
        };
        assertThatThrownBy(() -> fake.purgeAllVersionsVerified("org/doc"))
            .isInstanceOf(IOException.class).hasMessageContaining("not supported");
    }
}
