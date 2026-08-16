package ru.safeai.gateway.knowledge.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

@Component
@ConditionalOnProperty(
        prefix = "safeai.knowledge.storage",
        name = "type",
        havingValue = "local",
        matchIfMissing = true
)
public class LocalObjectStorage
        implements ObjectStorage {

    private static final String INVALID_KEY_MESSAGE =
            "Некорректный storage key";

    private final Path root;

    public LocalObjectStorage(
            KnowledgeStorageProperties properties
    ) throws IOException {
        Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        root = properties.localRoot()
                .toAbsolutePath()
                .normalize();

        Files.createDirectories(
                root
        );
    }

    @Override
    public void put(
            String key,
            InputStream content
    ) throws IOException {
        Objects.requireNonNull(
                content,
                "content не должен быть null"
        );

        Path target =
                resolve(
                        key
                );

        Path parent =
                target.getParent();

        Files.createDirectories(
                parent
        );

        Path temp =
                Files.createTempFile(
                        parent,
                        "upload-",
                        ".tmp"
                );

        try {
            Files.copy(
                    content,
                    temp,
                    StandardCopyOption.REPLACE_EXISTING
            );

            moveIntoPlace(
                    temp,
                    target
            );
        } finally {
            Files.deleteIfExists(
                    temp
            );
        }
    }

    @Override
    public StoredObject get(
            String key
    ) throws IOException {
        Path path =
                resolve(
                        key
                );

        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(
                    key
            );
        }

        return new StoredObject(
                new FileSystemResource(
                        path
                ),
                Files.size(
                        path
                )
        );
    }

    @Override
    public void delete(
            String key
    ) throws IOException {
        Files.deleteIfExists(
                resolve(
                        key
                )
        );
    }

    private void moveIntoPlace(
            Path source,
            Path target
    ) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private Path resolve(
            String key
    ) {
        Path relative =
                normalizeKey(
                        key
                );

        return resolveInsideRoot(
                relative
        );
    }

    private Path normalizeKey(
            String key
    ) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "Storage key не должен быть пустым"
            );
        }

        Path relative =
                Path.of(
                        key
                )
                        .normalize();

        if (relative.isAbsolute()
                || relative.toString().isBlank()
                || relative.startsWith("..")) {
            throw new IllegalArgumentException(
                    INVALID_KEY_MESSAGE
            );
        }

        return relative;
    }

    private Path resolveInsideRoot(
            Path relative
    ) {
        Path resolved =
                root.resolve(
                        relative
                )
                        .normalize();

        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    INVALID_KEY_MESSAGE
            );
        }

        return resolved;
    }
}