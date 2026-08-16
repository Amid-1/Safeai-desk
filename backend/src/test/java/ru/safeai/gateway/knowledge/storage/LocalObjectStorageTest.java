package ru.safeai.gateway.knowledge.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalObjectStorageTest {

    private static final long MAX_UPLOAD_BYTES =
            26_214_400L;

    private static final String TEST_BUCKET =
            "safeai-knowledge";

    @TempDir
    Path tempDir;

    @Test
    void putGetDelete_roundTripsBytes() throws IOException {
        LocalObjectStorage storage =
                storage();

        byte[] bytes =
                "SafeAI knowledge object"
                        .getBytes(StandardCharsets.UTF_8);

        storage.put(
                "org/kb/document/version",
                new ByteArrayInputStream(bytes)
        );

        StoredObject object =
                storage.get(
                        "org/kb/document/version"
                );

        assertThat(object.contentLength())
                .isEqualTo(bytes.length);

        assertThat(
                object.resource()
                        .getContentAsByteArray()
        ).containsExactly(bytes);

        storage.delete(
                "org/kb/document/version"
        );

        assertThatThrownBy(
                () -> storage.get(
                        "org/kb/document/version"
                )
        ).isInstanceOf(
                NoSuchFileException.class
        );
    }

    @Test
    void put_overwritesExistingObject()
            throws IOException {
        LocalObjectStorage storage =
                storage();

        storage.put(
                "same/key",
                new ByteArrayInputStream(
                        "v1".getBytes(
                                StandardCharsets.UTF_8
                        )
                )
        );

        storage.put(
                "same/key",
                new ByteArrayInputStream(
                        "version-2".getBytes(
                                StandardCharsets.UTF_8
                        )
                )
        );

        assertThat(
                storage.get("same/key")
                        .resource()
                        .getContentAsString(
                                StandardCharsets.UTF_8
                        )
        ).isEqualTo("version-2");
    }

    @Test
    void delete_isIdempotentForMissingObject()
            throws IOException {
        LocalObjectStorage storage =
                storage();

        storage.delete("missing/key");
        storage.delete("missing/key");
    }

    @Test
    void rejectsBlankParentTraversalAndAbsoluteKeys()
            throws IOException {
        LocalObjectStorage storage =
                storage();

        assertThatThrownBy(
                () -> storage.put(
                        " ",
                        new ByteArrayInputStream(
                                new byte[]{1}
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "не должен быть пустым"
                );

        assertThatThrownBy(
                () -> storage.put(
                        "../outside.txt",
                        new ByteArrayInputStream(
                                new byte[]{1}
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "Некорректный storage key"
                );

        String absolute =
                tempDir.resolve(
                                "outside.txt"
                        )
                        .toAbsolutePath()
                        .toString();

        assertThatThrownBy(
                () -> storage.put(
                        absolute,
                        new ByteArrayInputStream(
                                new byte[]{1}
                        )
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }

    private LocalObjectStorage storage()
            throws IOException {
        return new LocalObjectStorage(
                new KnowledgeStorageProperties(
                        KnowledgeStorageType.LOCAL,
                        tempDir,
                        MAX_UPLOAD_BYTES,
                        null,
                        null,
                        null,
                        TEST_BUCKET
                )
        );
    }
}