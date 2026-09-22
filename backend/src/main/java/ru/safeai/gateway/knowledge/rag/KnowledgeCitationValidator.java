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
     * Application-looking marker, including malformed candidates. Cyrillic С
     * is matched explicitly to prevent homoglyph citations from looking
     * authoritative in the visible response.
     *
     * Valid citation syntax remains EXACTLY [C1]..[C999] (ASCII C, optional
     * ASCII case folding, without spaces or leading zeroes). Invalid candidates
     * are removed atomically with all valid citations on any invalidity.
     *
     * The bounded character class intentionally does not treat arbitrary
     * bracketed prose (e.g. [Customer]) as an application citation.
     */
    private static final Pattern APPLICATION_CITATION_CANDIDATE =
            Pattern.compile(
                    "\\[[ \t]*[Cc\u0421\u0441][ \t]*([0-9]*|[a-zA-Z\u0430-\u044f\u0410-\u042f]{1,3}[0-9]{0,3})[ \t]*]"
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

        preparation.sources()
                .forEach(
                        source -> {
                            Objects.requireNonNull(
                                    source,
                                    "Knowledge source не должен быть null"
                            );

                            allowed.put(
                                    source.label(),
                                    source
                            );
                        }
                );

        Matcher matcher =
                APPLICATION_CITATION_CANDIDATE.matcher(
                        response.content()
                );

        Map<String, RagCitation> citations =
                new LinkedHashMap<>();

        boolean valid =
                true;

        StringBuilder sanitized =
                new StringBuilder(
                        response.content().length()
                );

        while (matcher.find()) {
            Matcher canonical = VALID_NUMERIC_CITATION.matcher(matcher.group());
            Integer ordinal = canonical.matches()
                    ? parseOrdinal(canonical.group(2))
                    : null;

            /*
             * Malformed numeric application citation.
             *
             * Remove it from visible text and mark the complete citation set
             * invalid.
             */
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

            /*
             * Syntactically valid marker, but it does not refer to a source
             * actually materialized for this exact RAG request.
             */
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

            /*
             * Canonicalize accepted marker representation.
             */
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

        String content =
                sanitized.toString()
                        .strip();

        /*
         * Critical persistence invariant.
         *
         * Citation validation is atomic.
         *
         * Example:
         *
         *     "... [C1] ... [C999] ..."
         *
         * C1 may be a legitimate source, but once C999 is encountered the
         * model output as a whole has produced an invalid structured citation
         * set.
         *
         * We therefore must never return:
         *
         *     citationsValid = false
         *     citations      = [C1]
         *
         * because Answer Passport correctly rejects that inconsistent state.
         *
         * Instead:
         *
         *     citationsValid      = false
         *     citations           = []
         *     evidenceSufficient  = false
         *
         * All remaining numeric application citation markers are also removed
         * from visible answer text so that invalid output cannot leave a
         * visually authoritative-looking citation behind.
         */
        if (!valid) {
            citations.clear();

            content =
                    APPLICATION_CITATION_CANDIDATE
                            .matcher(
                                    content
                            )
                            .replaceAll(
                                    ""
                            )
                            .strip();
        }

        boolean evidenceSufficient =
                valid
                        && !allowed.isEmpty()
                        && !citations.isEmpty();

        /*
         * KNOWLEDGE_ONLY is fail-closed.
         *
         * Without a completely valid citation set backed by the exact
         * materialized sources, the answer is replaced by the canonical
         * abstention response.
         */
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
        /*
         * Application-controlled citation namespace:
         *
         * C1 .. C999
         *
         * Leading zeroes are rejected so that [C01] cannot become an alias
         * for [C1].
         */
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
