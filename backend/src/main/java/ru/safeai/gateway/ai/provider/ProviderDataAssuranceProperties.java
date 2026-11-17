package ru.safeai.gateway.ai.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independent attestations: a chat agreement never authorizes embeddings or OCR. */
@ConfigurationProperties(prefix = "safeai.provider-data-assurance", ignoreUnknownFields = false)
public record ProviderDataAssuranceProperties(
        ProviderDataEvidence chat, ProviderDataEvidence embedding, ProviderDataEvidence ocr
) {
    public static final ProviderDataAssuranceProperties EMPTY =
            new ProviderDataAssuranceProperties(null, null, null);
}
