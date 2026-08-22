package ru.safeai.gateway.knowledge.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@Profile({"prod", "production"})
public class KnowledgeStorageProductionInvariantVerifier {

    private final KnowledgeStorageProperties properties;

    public KnowledgeStorageProductionInvariantVerifier(
            KnowledgeStorageProperties properties
    ) {
        this.properties = properties;
    }

    @PostConstruct
    void verify() {
        if (properties.type()
                != KnowledgeStorageType.S3) {
            throw new IllegalStateException(
                    "Production Knowledge storage должен использовать S3. "
                            + "LOCAL storage небезопасен для multi-instance deployment."
            );
        }

        URI endpoint =
                URI.create(
                        properties.endpoint()
                );

        if (!"https".equalsIgnoreCase(
                endpoint.getScheme()
        )) {
            throw new IllegalStateException(
                    "Production Knowledge S3 endpoint должен использовать HTTPS"
            );
        }
    }
}
