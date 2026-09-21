package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class LocalObjectStorageFailureRegressionTest {
    @TempDir Path directory;

    @Test
    void failedWritePreservesLastCommittedObjectAndRemovesTemporaryFile() throws Exception {
        var storage = storage();
        byte[] original = "committed".getBytes(StandardCharsets.UTF_8);
        storage.put("org/key", new ByteArrayInputStream(original));
        try (InputStream failingStream = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("source stream failure"); }
        }) {
            assertThatThrownBy(() -> storage.put("org/key", failingStream))
                    .isInstanceOf(IOException.class);
        }
        assertThat(storage.get("org/key").resource().getContentAsByteArray())
                .containsExactly(original);
        try (var entries = Files.list(directory.resolve("org"))) {
            assertThat(entries.map(Path::getFileName).map(Path::toString).toList())
                    .containsExactly("key");
        }
    }

    @Test
    void malformedKeysCannotEscapeLocalStorageRoot() throws Exception {
        var storage = storage();
        assertThatThrownBy(() -> storage.put("../../escape.txt", new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("/tmp/escape.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get("../escape.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingKeyCannotBeConfusedWithZeroLengthObject() throws Exception {
        var storage = storage();
        storage.put("org/empty", new ByteArrayInputStream(new byte[0]));
        assertThat(storage.get("org/empty").contentLength()).isZero();
        assertThatThrownBy(() -> storage.get("org/missing"))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
    }

    private LocalObjectStorage storage() throws IOException {
        return new LocalObjectStorage(new KnowledgeStorageProperties(
                KnowledgeStorageType.LOCAL, directory, 26_214_400L,
                null, null, null, "safeai-knowledge"));
    }
}
