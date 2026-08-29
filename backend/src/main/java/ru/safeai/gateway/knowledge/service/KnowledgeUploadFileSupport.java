package ru.safeai.gateway.knowledge.service;

import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

final class KnowledgeUploadFileSupport {

    private static final int MAX_FILENAME_CODE_POINTS = 255;

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(
                    "pdf",
                    "docx",
                    "txt",
                    "html",
                    "htm",
                    "md",
                    "csv",
                    "xlsx",
                    "pptx",
                    "json",
                    "xml"
            );

    private KnowledgeUploadFileSupport() {
    }

    static void requireNonEmptyFile(
            MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(
                    "Выберите непустой файл."
            );
        }
    }

    static void requireWithinUploadLimit(
            long size,
            long maxUploadBytes
    ) {
        if (size < 1L) {
            throw new BadRequestException(
                    "Выберите непустой файл."
            );
        }

        if (size > maxUploadBytes) {
            throw KnowledgeValidationErrors.fileTooLarge(
                    maxUploadBytes
            );
        }
    }

    static byte[] readFileBytes(
            MultipartFile file
    ) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BadRequestException(
                    "Не удалось прочитать загружаемый файл.",
                    exception
            );
        }
    }

    static String safeFilename(
            String value
    ) {
        requireOriginalFilename(value);
        String filename = filenameBasename(value);
        validateFilename(filename);
        return filename;
    }

    static void requireSupportedExtension(
            String extension
    ) {
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw KnowledgeValidationErrors.unsupportedType();
        }
    }

    static String extension(
            String filename
    ) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }

        return filename.substring(dot + 1)
                .toLowerCase(Locale.ROOT);
    }

    static String sha256(
            byte[] bytes
    ) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 недоступен в текущем Java runtime",
                    exception
            );
        }
    }

    private static void requireOriginalFilename(
            String value
    ) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(
                    "Исходное имя файла отсутствует."
            );
        }
    }

    private static String filenameBasename(
            String value
    ) {
        String normalizedPath = value.replace('\\', '/');
        return normalizedPath
                .substring(normalizedPath.lastIndexOf('/') + 1)
                .strip();
    }

    private static void validateFilename(
            String filename
    ) {
        if (filename.isEmpty()) {
            throw new BadRequestException(
                    "Исходное имя файла отсутствует."
            );
        }

        if (filename.codePointCount(0, filename.length())
                > MAX_FILENAME_CODE_POINTS) {
            throw new BadRequestException(
                    "Имя файла не должно превышать "
                            + MAX_FILENAME_CODE_POINTS
                            + " символов."
            );
        }

        if (filename.codePoints()
                .anyMatch(Character::isISOControl)) {
            throw new BadRequestException(
                    "Имя файла содержит недопустимые управляющие символы."
            );
        }
    }
}
