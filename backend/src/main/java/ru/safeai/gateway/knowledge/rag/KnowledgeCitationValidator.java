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

    /*
     * Match every numeric application-looking citation marker first.
     *
     * Validation then decides whether the ordinal is syntactically legal
     * and belongs to the exact source set materialized for this request.
     *
     * Examples caught here:
     *
     * [C0]
     * [C01]
     * [C999]
     * [C1000]
     *
     * Case-insensitive matching is intentional; accepted markers are
     * canonicalized back to uppercase [C<n>].
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
                NUMERIC_CITATION.matcher(
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
            String rawOrdinal =
                    matcher.group(
                            1
                    );

            Integer ordinal =
                    parseOrdinal(
                            rawOrdinal
                    );

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
                    NUMERIC_CITATION
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