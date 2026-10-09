package ru.safeai.gateway.chat.dto;

/**
 * Title validation is intentionally centralized in ChatTitlePolicy.
 * Jakarta @Size counts UTF-16 code units and is inconsistent with the
 * code-point-based domain/DB contract for supplementary Unicode.
 */
public record CreateChatRequest(
        String title
) {
}
