package ru.safeai.gateway.knowledge.rag;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KnowledgeCitationValidator {

    public static final String ABSTENTION =
            "Недостаточно данных в разрешённой базе знаний.";

    private static final Pattern CITATION =
            Pattern.compile("\\[C([1-9][0-9]{0,2})]", Pattern.CASE_INSENSITIVE);

    public RagCompletion validate(
            RagPreparation preparation,
            AiChatResponse response
    ) {
        if (!preparation.usesKnowledge()) {
            return RagCompletion.general(preparation, response);
        }

        Map<String, KnowledgeContextSource> allowed = new LinkedHashMap<>();
        preparation.sources().forEach(source ->
                allowed.put(source.label(), source)
        );

        Matcher matcher = CITATION.matcher(response.content());
        Map<String, RagCitation> citations = new LinkedHashMap<>();
        boolean valid = true;
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            String label = "C" + matcher.group(1);
            KnowledgeContextSource source = allowed.get(label);
            if (source == null) {
                valid = false;
                matcher.appendReplacement(sanitized, "");
            } else {
                citations.putIfAbsent(
                        label,
                        new RagCitation(
                                label,
                                Integer.parseInt(matcher.group(1)),
                                source.hit().chunkId()
                        )
                );
                matcher.appendReplacement(
                        sanitized,
                        Matcher.quoteReplacement("[" + label + "]")
                );
            }
        }
        matcher.appendTail(sanitized);

        boolean evidenceSufficient = !allowed.isEmpty()
                && !citations.isEmpty()
                && valid;
        String content = sanitized.toString().strip();

        if (preparation.mode() == KnowledgeMode.KNOWLEDGE_ONLY
                && (!evidenceSufficient || content.isBlank())) {
            content = ABSTENTION;
            citations.clear();
            evidenceSufficient = false;
        }

        return new RagCompletion(
                preparation,
                withContent(response, content),
                new ArrayList<>(citations.values()),
                valid,
                evidenceSufficient
        );
    }

    private static AiChatResponse withContent(
            AiChatResponse response,
            String content
    ) {
        return new AiChatResponse(
                content,
                response.requestedModel(),
                response.model(),
                response.providerMessageId(),
                response.providerRequestId(),
                response.responseStatus(),
                response.finishReason(),
                response.inputTokens(),
                response.outputTokens(),
                response.usageStatus(),
                response.costUsd(),
                response.pricingStatus(),
                response.currency(),
                response.priceVersion(),
                response.pricingCalculatedAt()
        );
    }
}
