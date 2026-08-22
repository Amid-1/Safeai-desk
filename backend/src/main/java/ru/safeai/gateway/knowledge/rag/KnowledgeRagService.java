package ru.safeai.gateway.knowledge.rag;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.config.KnowledgeRagProperties;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalExecution;
import ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService;

@Service
public class KnowledgeRagService {

    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeContextAssembler contextAssembler;
    private final KnowledgeCitationValidator citationValidator;
    private final KnowledgeRagProperties properties;

    public KnowledgeRagService(
            KnowledgeRetrievalService retrievalService,
            KnowledgeContextAssembler contextAssembler,
            KnowledgeCitationValidator citationValidator,
            KnowledgeRagProperties properties
    ) {
        this.retrievalService = retrievalService;
        this.contextAssembler = contextAssembler;
        this.citationValidator = citationValidator;
        this.properties = properties;
    }

    public RagPreparation prepare(
            ChatProcessingContext context,
            SafeAiUserPrincipal user
    ) {
        if (!context.knowledgeMode().usesKnowledge()) {
            return RagPreparation.general(context.aiRequest());
        }
        KnowledgeRetrievalExecution retrieval =
                retrievalService.retrieveForChat(
                        context.knowledgeBaseId(),
                        context.turnId(),
                        context.aiRequest().userMessage(),
                        properties.topK(),
                        user
                );
        KnowledgeContextAssembler.AssembledContext assembled =
                contextAssembler.assemble(
                        context.knowledgeMode(),
                        retrieval,
                        context.aiRequest()
                );
        return new RagPreparation(
                context.knowledgeMode(),
                context.knowledgeBaseId(),
                retrieval.retrievalRunId(),
                retrieval.embeddingModel(),
                assembled.contextSha256(),
                assembled.sources(),
                assembled.request()
        );
    }

    public RagCompletion complete(
            RagPreparation preparation,
            AiChatResponse response
    ) {
        return citationValidator.validate(preparation, response);
    }
}
