// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/knowledge/rag/KnowledgeRagService.java
package ru.safeai.gateway.knowledge.rag;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.config.KnowledgeRagProperties;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalExecution;
import ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService;

import java.util.Objects;
import java.util.UUID;

@Service
public class KnowledgeRagService {

    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeContextAssembler contextAssembler;
    private final KnowledgeCitationValidator citationValidator;
    private final KnowledgeRagContextReceiptService contextReceiptService;
    private final KnowledgeRagProperties properties;
    private final KnowledgeRagExecutionPermitService executionPermitService;

    /** Source-compatible constructor for isolated unit tests. Production uses the permit-aware constructor. */
    public KnowledgeRagService(
            KnowledgeRetrievalService retrievalService,
            KnowledgeContextAssembler contextAssembler,
            KnowledgeCitationValidator citationValidator,
            KnowledgeRagContextReceiptService contextReceiptService,
            KnowledgeRagProperties properties
    ) {
        this(retrievalService, contextAssembler, citationValidator, contextReceiptService,
                properties, null);
    }

    @Autowired
    public KnowledgeRagService(
            KnowledgeRetrievalService retrievalService,
            KnowledgeContextAssembler contextAssembler,
            KnowledgeCitationValidator citationValidator,
            KnowledgeRagContextReceiptService contextReceiptService,
            KnowledgeRagProperties properties,
            KnowledgeRagExecutionPermitService executionPermitService
    ) {
        this.retrievalService = Objects.requireNonNull(
                retrievalService,
                "retrievalService не должен быть null"
        );
        this.contextAssembler = Objects.requireNonNull(
                contextAssembler,
                "contextAssembler не должен быть null"
        );
        this.citationValidator = Objects.requireNonNull(
                citationValidator,
                "citationValidator не должен быть null"
        );
        this.contextReceiptService = Objects.requireNonNull(
                contextReceiptService,
                "contextReceiptService не должен быть null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
        this.executionPermitService = executionPermitService;
    }

    public RagPreparation prepare(
            KnowledgeChatContext context,
            SafeAiUserPrincipal user
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(user, "user не должен быть null");

        AiChatRequest baseRequest = Objects.requireNonNull(
                context.aiRequest(),
                "context.aiRequest не должен быть null"
        );

        if (!context.knowledgeMode().usesKnowledge()) {
            return RagPreparation.general(baseRequest);
        }

        if (context.knowledgeBaseId() == null) {
            throw new IllegalStateException(
                    "Knowledge RAG requires knowledgeBaseId"
            );
        }

        if (context.modelRouteDecisionId() != null
                && (baseRequest.reservedInputTokens() == null
                    || baseRequest.maxOutputTokens() == null)) {
            throw new IllegalStateException(
                    "Governed Knowledge RAG requires an exact model-route input/output envelope"
            );
        }

        if (!user.getOrganizationId().equals(baseRequest.organizationId())
                || !user.getId().equals(baseRequest.userId())
                || !context.chatId().equals(baseRequest.chatId())) {
            throw new IllegalStateException(
                    "Knowledge RAG principal/request identity mismatch"
            );
        }

        KnowledgeRetrievalExecution retrieval =
                Objects.requireNonNull(
                        retrievalService.retrieveForChat(
                                context.knowledgeBaseId(),
                                context.turnId(),
                                baseRequest.userMessage(),
                                properties.topK(),
                                user
                        ),
                        "Knowledge retrieval execution не должен быть null"
                );

        KnowledgeContextAssembler.AssembledContext assembled =
                Objects.requireNonNull(
                        contextAssembler.assemble(
                                context.knowledgeMode(),
                                retrieval,
                                baseRequest
                        ),
                        "assembled Knowledge context не должен быть null"
                );

        AiChatRequest preparedRequest = Objects.requireNonNull(
                assembled.request(),
                "assembled.request не должен быть null"
        );

        contextReceiptService.record(
                retrieval.retrievalRunId(),
                context.turnId(),
                user.getOrganizationId(),
                user.getId(),
                context.knowledgeBaseId(),
                retrieval.embeddingModel(),
                assembled.contextSha256(),
                preparedRequest
        );

        if (executionPermitService == null) {
            throw new IllegalStateException(
                    "Knowledge RAG execution permit service is required before provider dispatch");
        }
        UUID executionPermitId = executionPermitService.createPermit(
                context.turnId(),
                user.getOrganizationId(),
                context.knowledgeBaseId(),
                retrieval.retrievalRunId(),
                assembled.contextSha256(),
                assembled.sources()
        );

        return new RagPreparation(
                context.knowledgeMode(),
                context.knowledgeBaseId(),
                retrieval.retrievalRunId(),
                retrieval.embeddingModel(),
                assembled.contextSha256(),
                assembled.sources(),
                preparedRequest,
                executionPermitId
        );
    }

    /** Проверка уже сформированного контекста перед отправкой провайдеру. */
    public void assertSourcesAvailable(RagPreparation preparation, SafeAiUserPrincipal user) {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(user, "user");
        contextReceiptService.assertSourcesAvailable(preparation, user.getOrganizationId());
    }

    /** Release an unused/failed context permit; active physical attempt leases remain independently fenced. */
    public void closeExecutionPermit(RagPreparation preparation) {
        if (preparation != null && preparation.executionPermitId() != null
                && executionPermitService != null) {
            executionPermitService.closePermit(preparation.executionPermitId());
        }
    }

    public RagCompletion complete(
            RagPreparation preparation,
            AiChatResponse response
    ) {
        Objects.requireNonNull(preparation, "preparation не должен быть null");
        Objects.requireNonNull(response, "response не должен быть null");
        return citationValidator.validate(preparation, response);
    }
}
