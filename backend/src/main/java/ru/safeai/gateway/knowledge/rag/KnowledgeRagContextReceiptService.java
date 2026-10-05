package ru.safeai.gateway.knowledge.rag;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiRequestFingerprint;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Persists immutable proof of the exact Knowledge context and exact prepared
 * request before control returns to the chat execution boundary.
 *
 * <p>The bean is intentionally non-final because Spring may create a class-based
 * proxy for the {@link Transactional} boundary. The service itself is public
 * because it is part of the public constructor contract of {@link KnowledgeRagService};
 * its repository remains package-private implementation detail.</p>
 */
@Service
public class KnowledgeRagContextReceiptService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_EMBEDDING_MODEL_LENGTH = 128;

    private final KnowledgeRagContextReceiptRepository repository;
    private final Clock clock;

    KnowledgeRagContextReceiptService(
            KnowledgeRagContextReceiptRepository repository,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository не должен быть null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID retrievalRunId,
            UUID chatTurnId,
            UUID organizationId,
            UUID userId,
            UUID knowledgeBaseId,
            String embeddingModel,
            String contextSha256,
            AiChatRequest preparedRequest
    ) {
        Objects.requireNonNull(retrievalRunId, "retrievalRunId");
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        Objects.requireNonNull(preparedRequest, "preparedRequest");

        validateEmbeddingModel(embeddingModel);
        validateSha256(contextSha256, "contextSha256");
        requirePreparedRequestIdentity(
                preparedRequest,
                organizationId,
                userId
        );

        String preparedRequestSha256 =
                AiRequestFingerprint.preparedSha256(preparedRequest);
        validateSha256(preparedRequestSha256, "preparedRequestSha256");

        repository.insert(
                retrievalRunId,
                chatTurnId,
                organizationId,
                userId,
                knowledgeBaseId,
                embeddingModel,
                contextSha256,
                preparedRequestSha256,
                clock.instant()
        );
    }

    private static void requirePreparedRequestIdentity(
            AiChatRequest preparedRequest,
            UUID organizationId,
            UUID userId
    ) {
        if (!organizationId.equals(preparedRequest.organizationId())
                || !userId.equals(preparedRequest.userId())) {
            throw new IllegalStateException(
                    "Prepared Knowledge RAG request identity does not match retrieval scope"
            );
        }
    }

    private static void validateEmbeddingModel(String embeddingModel) {
        if (embeddingModel == null
                || embeddingModel.isBlank()
                || !embeddingModel.equals(embeddingModel.strip())
                || embeddingModel.length() > MAX_EMBEDDING_MODEL_LENGTH
                || embeddingModel.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "embeddingModel должен быть непустым trimmed-значением до "
                            + MAX_EMBEDDING_MODEL_LENGTH
                            + " символов без control characters"
            );
        }
    }

    private static void validateSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " должен быть lowercase SHA-256 hex"
            );
        }
    }
}
