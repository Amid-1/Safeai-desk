package ru.safeai.gateway.knowledge.service;

import ru.safeai.gateway.common.exception.BadRequestException;

final class KnowledgeValidationErrors {

    static final String SUPPORTED_FORMATS =
            "PDF, DOCX, TXT, HTML, MD, CSV, XLSX, PPTX, JSON и XML";

    private KnowledgeValidationErrors() {
    }

    static BadRequestException unsupportedType() {
        return new BadRequestException(
                "Поддерживаются только "
                        + SUPPORTED_FORMATS
                        + "."
        );
    }

    static BadRequestException extensionMismatch() {
        return new BadRequestException(
                "Расширение файла не соответствует его содержимому. "
                        + "Поддерживаются "
                        + SUPPORTED_FORMATS
                        + "."
        );
    }

    static BadRequestException invalidStructuredFormat(
            String format
    ) {
        return new BadRequestException(
                "Файл " + format
                        + " повреждён или имеет некорректную структуру."
        );
    }

    static BadRequestException fileTooLarge(
            long maxUploadBytes
    ) {
        return new BadRequestException(
                "Размер файла превышает допустимый лимит: "
                        + maxUploadBytes
                        + " байт."
        );
    }
}
