package ru.safeai.gateway.knowledge.extraction;

import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OoxmlPackageSupport {

    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final int BUFFER_SIZE = 8_192;

    private OoxmlPackageSupport() {
    }

    static void validate(
            byte[] content,
            String requiredEntry,
            long maximumUncompressedBytes,
            String format
    ) {
        long uncompressedBytes = 0L;
        int entries = 0;
        int requiredEntryCount = 0;

        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(content)
        )) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ARCHIVE_ENTRIES) {
                    throw invalid(format, "слишком много ZIP entries");
                }

                String entryName = entry.getName();
                if (isUnsafeEntryName(entryName)) {
                    throw invalid(format, "небезопасное имя ZIP entry");
                }
                if (requiredEntry.equals(entryName)) {
                    requiredEntryCount++;
                }

                int read;
                while ((read = zip.read(buffer)) != -1) {
                    uncompressedBytes = addExact(
                            uncompressedBytes,
                            read,
                            format
                    );
                    if (uncompressedBytes > maximumUncompressedBytes) {
                        throw invalid(
                                format,
                                "слишком большой распакованный размер"
                        );
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw invalid(format, "повреждённый ZIP container", exception);
        }

        if (requiredEntryCount != 1) {
            throw invalid(
                    format,
                    requiredEntry + " отсутствует или дублируется"
            );
        }
    }

    private static long addExact(long current, int read, String format) {
        try {
            return Math.addExact(current, read);
        } catch (ArithmeticException exception) {
            throw invalid(format, "переполнение распакованного размера");
        }
    }

    private static boolean isUnsafeEntryName(String name) {
        return name == null
                || name.isBlank()
                || name.startsWith("/")
                || name.contains("\\")
                || name.equals("..")
                || name.startsWith("../")
                || name.contains("/../");
    }

    private static KnowledgeIngestionException invalid(
            String format,
            String reason
    ) {
        return invalid(format, reason, null);
    }

    private static KnowledgeIngestionException invalid(
            String format,
            String reason,
            Throwable cause
    ) {
        return new KnowledgeIngestionException(
                "INVALID_" + format + "_ARCHIVE",
                "Некорректный " + format + ": " + reason,
                false,
                cause
        );
    }
}
