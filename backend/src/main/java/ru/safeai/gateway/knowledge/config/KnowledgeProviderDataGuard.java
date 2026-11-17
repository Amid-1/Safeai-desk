package ru.safeai.gateway.knowledge.config;

import ru.safeai.gateway.ai.provider.ProviderDataEvidence;

import java.time.Clock;

/** External KB operations may carry confidential data before a chat policy exists.
 * They must always satisfy both guarantees, independently of tenant settings. */
public final class KnowledgeProviderDataGuard {
    private KnowledgeProviderDataGuard() {}

    public static String embeddingBinding(KnowledgeEmbeddingProperties properties) {
        return ProviderDataEvidence.fingerprint("embedding", properties.provider(),
                properties.baseUrl(), properties.model(), properties.apiKey(),
                Integer.toString(properties.dimensions()));
    }

    public static String ocrBinding(KnowledgeOcrProperties properties) {
        return ProviderDataEvidence.fingerprint("ocr", properties.provider(),
                properties.endpoint(), properties.apiKey());
    }

    public static boolean permits(ProviderDataEvidence evidence, Clock clock,
                                  String endpoint, String binding) {
        if (evidence == null || !endpoint.startsWith("https://")) return false;
        var status = evidence.evaluate(clock.instant(), binding, true);
        return status.noTraining() && status.zeroDataRetention();
    }
}
