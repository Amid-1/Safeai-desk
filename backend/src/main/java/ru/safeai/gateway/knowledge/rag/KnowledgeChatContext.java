package ru.safeai.gateway.knowledge.rag;

import ru.safeai.gateway.ai.dto.AiChatRequest;

import java.util.UUID;

/**
 * Minimal immutable view of a chat turn needed for Knowledge RAG and
 * Answer Passport persistence. Owned by Knowledge, implemented by Chat.
 *
 * No replay, lease or processing-token state crosses this boundary.
 */
public interface KnowledgeChatContext {

    UUID chatId();

    UUID turnId();

    UUID modelRouteDecisionId();

    AiChatRequest aiRequest();

    UUID knowledgeBaseId();

    KnowledgeMode knowledgeMode();
}
