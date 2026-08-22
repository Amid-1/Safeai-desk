package ru.safeai.gateway.knowledge.rag;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KnowledgeCitationValidator {

    public static final String ABSTENTION =
            "Недостаточно данных в разрешённой базе знаний.";

    /*
     * Match every numeric citation-looking marker first. Validation then
     * decides whether its ordinal is legal and present in the exact source set.
     * This prevents malformed markers such as [C0] / [C1000] from surviving
     * as visually convincing but unverified citations.
     */
    private static final Pattern NUMERIC_CITATION =
            Pattern.compile(
                    "\\[C([0-9]+)]",
                    Pattern.CASE_INSENSITIVE
            );

    public RagCompletion validate(
            RagPreparation preparation,
            AiChatResponse response
    ) {
        if (!preparation.mode().usesKnowledge()) {
            return RagCompletion.general(
                    preparation,
                    response
            );
        }

        Map<String, KnowledgeContextSource> allowed =
                new LinkedHashMap<>();

        preparation.sources()
                .forEach(
                        source ->
                                allowed.put(
                                        source.label(),
                                        source
                                )
                );

        Matcher matcher =
                NUMERIC_CITATION.matcher(
                        response.content()
                );

        Map<String, RagCitation> citations =
                new LinkedHashMap<>();

        boolean valid =
                true;

        StringBuilder sanitized =
                new StringBuilder();

        while (matcher.find()) {
            String rawOrdinal =
                    matcher.group(1);

            Integer ordinal =
                    parseOrdinal(
                            rawOrdinal
                    );

            if (ordinal == null) {
                valid =
                        false;

                matcher.appendReplacement(
                        sanitized,
                        ""
                );
                continue;
            }

            String label =
                    "C" + ordinal;

            KnowledgeContextSource source =
                    allowed.get(
                            label
                    );

            if (source == null) {
                valid =
                        false;

                matcher.appendReplacement(
                        sanitized,
                        ""
                );
                continue;
            }

            citations.putIfAbsent(
                    label,
                    new RagCitation(
                            label,
                            ordinal,
                            source.hit()
                                    .chunkId()
                    )
            );

            matcher.appendReplacement(
                    sanitized,
                    Matcher.quoteReplacement(
                            "[" + label + "]"
                    )
            );
        }

        matcher.appendTail(
                sanitized
        );

        boolean evidenceSufficient =
                !allowed.isEmpty()
                        && !citations.isEmpty()
                        && valid;

        String content =
                sanitized.toString()
                        .strip();

        if (preparation.mode()
                == KnowledgeMode.KNOWLEDGE_ONLY
                && (
                !evidenceSufficient
                        || content.isBlank()
        )) {
            content =
                    ABSTENTION;

            citations.clear();
            evidenceSufficient =
                    false;
        }

        return new RagCompletion(
                preparation,
                withContent(
                        response,
                        content
                ),
                new ArrayList<>(
                        citations.values()
                ),
                valid,
                evidenceSufficient
        );
    }

    private static Integer parseOrdinal(
            String value
    ) {
        if (value == null
                || value.isEmpty()
                || value.length() > 3
                || value.charAt(0) == '0') {
            return null;
        }

        try {
            int ordinal =
                    Integer.parseInt(
                            value
                    );

            return ordinal >= 1
                    && ordinal <= 999
                    ? ordinal
                    : null;
        } catch (NumberFormatException exception) {
            return null;
        }
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
