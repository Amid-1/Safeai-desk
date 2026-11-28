package ru.safeai.gateway.ai.provider;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Do not persist or log a provider's reflected authentication material. */
public final class ProviderCredentialEvidence {
    private ProviderCredentialEvidence() {}

    public static boolean reflected(JsonNode body, HttpHeaders headers, String key) {
        return (body != null && contains(body.toString(), key))
                || (headers != null && headers.headerSet().stream()
                    .flatMap(header -> header.getValue().stream()).anyMatch(value -> contains(value, key)));
    }

    public static boolean reflected(RestClientResponseException response, String key) {
        String body = response.getResponseBodyAsString();
        return contains(body, key)
                || reflected(AiJsonNodeSupport.parseOrNull(body), response.getResponseHeaders(), key);
    }

    private static boolean contains(String value, String key) {
        return key != null && !key.isEmpty() && value.contains(key);
    }
}
