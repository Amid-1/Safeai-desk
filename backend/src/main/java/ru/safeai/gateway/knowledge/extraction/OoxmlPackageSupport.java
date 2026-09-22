package ru.safeai.gateway.knowledge.extraction;

import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A single bounded OOXML ZIP scanner used at upload and at ingestion.
 *
 * <p>Both the count of entries and ALL inflated bytes are bounded while
 * reading each entry. The content types manifest is retained only up to its
 * independent limit; other entries are drained into a fixed-size buffer.</p>
 */
public final class OoxmlPackageSupport {

    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final int BUFFER_SIZE = 8_192;
    private static final int MAX_CONTENT_TYPES_BYTES = 256 * 1_024;
    private static final String CONTENT_TYPES = "[Content_Types].xml";

    private OoxmlPackageSupport() { }

    public static void validate(
            byte[] content,
            String requiredEntry,
            long maximumUncompressedBytes,
            String format
    ) {
        Objects.requireNonNull(requiredEntry, "requiredEntry");
        ArchiveIndex index = inspect(content, maximumUncompressedBytes, format);
        if (index.count(requiredEntry) != 1) {
            throw invalid(format, requiredEntry + " отсутствует или дублируется");
        }
    }

    /**
     * Never expose the mutable input manifest array outside this value object.
     */
    public record ArchiveIndex(Map<String, Integer> entries, byte[] contentTypes) {
        public ArchiveIndex {
            entries = Map.copyOf(entries);
            contentTypes = contentTypes == null ? null : contentTypes.clone();
        }

        @Override
        public byte[] contentTypes() {
            return contentTypes == null ? null : contentTypes.clone();
        }

        public int count(String entryName) {
            return entries.getOrDefault(entryName, 0);
        }
    }

    public static ArchiveIndex inspect(
            byte[] content,
            long maximumUncompressedBytes,
            String format
    ) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(format, "format");
        if (maximumUncompressedBytes <= 0) {
            throw new IllegalArgumentException("maximumUncompressedBytes must be positive");
        }

        long inflated = 0L;
        int entryCount = 0;
        byte[] contentTypes = null;
        Map<String, Integer> entries = new HashMap<>();
        byte[] buffer = new byte[BUFFER_SIZE];

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw invalid(format, "слишком много ZIP entries");
                }

                String name = entry.getName();
                if (isUnsafeEntryName(name)) {
                    throw invalid(format, "небезопасное имя ZIP entry");
                }
                entries.merge(name, 1, Integer::sum);

                ByteArrayOutputStream manifest = CONTENT_TYPES.equals(name)
                        ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    if (read > maximumUncompressedBytes - inflated) {
                        throw invalid(format, "слишком большой распакованный размер");
                    }
                    inflated += read;
                    if (manifest != null) {
                        if (read > MAX_CONTENT_TYPES_BYTES - manifest.size()) {
                            throw invalid(format, "[Content_Types].xml слишком большой");
                        }
                        manifest.write(buffer, 0, read);
                    }
                }
                if (manifest != null) {
                    contentTypes = manifest.toByteArray();
                }
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw invalid(format, "повреждённый ZIP container", exception);
        }

        return new ArchiveIndex(entries, contentTypes);
    }

    private static boolean isUnsafeEntryName(String name) {
        return name == null || name.isBlank() || name.startsWith("/")
                || name.contains("\\") || name.equals("..")
                || name.startsWith("../") || name.contains("/../");
    }

    private static KnowledgeIngestionException invalid(String format, String reason) {
        return invalid(format, reason, null);
    }

    private static KnowledgeIngestionException invalid(
            String format, String reason, Throwable cause
    ) {
        return new KnowledgeIngestionException(
                "INVALID_" + format + "_ARCHIVE",
                "Некорректный " + format + ": " + reason,
                false,
                cause
        );
    }
}
