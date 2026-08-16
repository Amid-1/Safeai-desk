package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtConfigurationBindingTest {

    private static final String LOCAL_ISSUER =
            String.join(
                    "",
                    "http",
                    "://",
                    "localhost:9999"
            );

    private static final String ACTIVE_KEY_ID =
            "test-active-key";

    private static final String PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            test-public-key
            -----END PUBLIC KEY-----\
            """;

    private static final String PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            test-private-key
            -----END PRIVATE KEY-----\
            """;

    @Test
    void safeAiEnvironmentVariablesAreBridgedThroughApplicationYml()
            throws IOException {

        StandardEnvironment environment =
                new StandardEnvironment();

        environment.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "safeai-test-.env",
                                Map.of(
                                        "SAFEAI_JWT_EXPIRATION_MINUTES",
                                        "17",
                                        "SAFEAI_JWT_ISSUER",
                                        LOCAL_ISSUER,
                                        "SAFEAI_JWT_AUDIENCE",
                                        "test-audience",
                                        "SAFEAI_JWT_ACTIVE_KEY_ID",
                                        ACTIVE_KEY_ID,
                                        "SAFEAI_JWT_PUBLIC_KEY",
                                        PUBLIC_KEY,
                                        "SAFEAI_JWT_PRIVATE_KEY",
                                        PRIVATE_KEY
                                )
                        )
                );

        YamlPropertySourceLoader loader =
                new YamlPropertySourceLoader();

        List<PropertySource<?>> sources =
                loader.load(
                        "application",
                        new ClassPathResource(
                                "application.yml"
                        )
                );

        for (PropertySource<?> source : sources) {
            environment.getPropertySources()
                    .addLast(source);
        }

        JwtProperties properties =
                Binder.get(environment)
                        .bind(
                                "app.security.jwt",
                                JwtProperties.class
                        )
                        .orElseThrow(() ->
                                new AssertionError(
                                        "app.security.jwt "
                                                + "configuration was not bound"
                                )
                        );

        assertThat(
                properties.expirationMinutes()
        ).isEqualTo(
                17L
        );

        assertThat(
                properties.issuer()
        ).isEqualTo(
                LOCAL_ISSUER
        );

        assertThat(
                properties.audience()
        ).isEqualTo(
                "test-audience"
        );

        assertThat(
                properties.activeKeyId()
        ).isEqualTo(
                ACTIVE_KEY_ID
        );

        assertThat(
                properties.keys()
        ).hasSize(
                1
        );

        JwtProperties.KeyEntry activeKey =
                properties.keys()
                        .getFirst();

        assertThat(
                activeKey.id()
        ).isEqualTo(
                ACTIVE_KEY_ID
        );

        assertThat(
                activeKey.publicKey()
        ).isEqualTo(
                PUBLIC_KEY
        );

        assertThat(
                activeKey.privateKey()
        ).isEqualTo(
                PRIVATE_KEY
        );
    }
}