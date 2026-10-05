package ru.safeai.gateway.knowledge.rag;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KnowledgeCitationValidator {

    public static final String ABSTENTION =
            "Недостаточно данных в разрешённой базе знаний.";

    /**
     * Application-looking marker, including malformed candidates.
     *
     * <p>Cyrillic С/с is matched explicitly to prevent homoglyph citations from
     * looking authoritative in the visible response.</p>
     *
     * <p>Valid citation syntax remains exactly [C1]..[C999] (ASCII C/c,
     * without spaces or leading zeroes). Invalid candidates are removed
     * atomically with all valid citations on any invalidity.</p>
     *
     * <p>The bounded character class intentionally does not treat arbitrary
     * bracketed prose such as [Customer] as an application citation.</p>
     */
    private static final Pattern APPLICATION_CITATION_CANDIDATE =
            Pattern.compile(
                    "\\[[ \t]*[CcСс][ \t]*"
                            + "([0-9]*|[a-zA-Zа-яА-Я]{1,3}[0-9]{0,3})"
                            + "[ \t]*]"
            );

    private static final Pattern VALID_NUMERIC_CITATION =
            Pattern.compile("\\[([Cc])([0-9]+)]");

    public RagCompletion validate(
            RagPreparation preparation,
            AiChatResponse response
    ) {
        Objects.requireNonNull(
                preparation,
                "preparation не должен быть null"
        );
        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

        if (!preparation.mode().usesKnowledge()) {
            return RagCompletion.general(
                    preparation,
                    response
            );
        }

        Map<String, KnowledgeContextSource> allowed =
                new LinkedHashMap<>();

        preparation.sources().forEach(source -> {
            Objects.requireNonNull(
                    source,
                    "Knowledge source не должен быть null"
            );

            allowed.put(
                    source.label(),
                    source
            );
        });

        String originalContent = Objects.requireNonNull(
                response.content(),
                "response.content не должен быть null"
        );

        Matcher matcher =
                APPLICATION_CITATION_CANDIDATE.matcher(
                        originalContent
                );

        Map<String, RagCitation> citations =
                new LinkedHashMap<>();

        boolean valid = true;

        StringBuilder sanitized =
                new StringBuilder(
                        originalContent.length()
                );

        while (matcher.find()) {
            Matcher canonical =
                    VALID_NUMERIC_CITATION.matcher(
                            matcher.group()
                    );

            Integer ordinal =
                    canonical.matches()
                            ? parseOrdinal(canonical.group(2))
                            : null;

            /*
             * Malformed application citation:
             * remove it from visible text and invalidate the complete
             * structured citation set.
             */
            if (ordinal == null) {
                valid = false;
                matcher.appendReplacement(
                        sanitized,
                        ""
                );
                continue;
            }

            String label =
                    "C" + ordinal;

            KnowledgeContextSource source =
                    allowed.get(label);

            /*
             * Syntactically valid marker that does not refer to a source
             * materialized for this exact RAG request.
             */
            if (source == null) {
                valid = false;
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
                            source.hit().chunkId()
                    )
            );

            matcher.appendReplacement(
                    sanitized,
                    Matcher.quoteReplacement(
                            "[" + label + "]"
                    )
            );
        }

        matcher.appendTail(sanitized);

        String content =
                sanitized.toString().strip();

        /*
         * Citation validation is atomic. Once any application citation is
         * malformed or unbound, no structured citation may survive as trusted
         * evidence and no authoritative-looking application marker may remain
         * in visible text.
         */
        if (!valid) {
            citations.clear();

            content =
                    APPLICATION_CITATION_CANDIDATE
                            .matcher(content)
                            .replaceAll("")
                            .strip();
        }

        boolean evidenceSufficient =
                valid
                        && !allowed.isEmpty()
                        && !citations.isEmpty();

        /*
         * KNOWLEDGE_ONLY is fail-closed: without a completely valid citation
         * set backed by the exact materialized sources, replace the answer with
         * the canonical abstention response.
         */
        if (preparation.mode() == KnowledgeMode.KNOWLEDGE_ONLY
                && (!evidenceSufficient || content.isBlank())) {

            content = ABSTENTION;
            citations.clear();
            evidenceSufficient = false;
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
        /*
         * Application-controlled citation namespace: C1..C999.
         * Leading zeroes are rejected so [C01] cannot alias [C1].
         */
        if (value == null
                || value.isEmpty()
                || value.length() > 3
                || value.charAt(0) == '0') {
            return null;
        }

        try {
            int ordinal =
                    Integer.parseInt(value);

            return ordinal >= 1 && ordinal <= 999
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
                response.cachedInputTokens(),
                response.cacheWriteInputTokens(),
                response.specializedBillingDimensionsPresent(),
                response.specializedBillingDimensionsValid(),
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
