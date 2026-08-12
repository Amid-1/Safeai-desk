package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Base64;
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

    @Test
    void safeAiEnvironmentVariablesAreBridgedThroughApplicationYaml()
            throws IOException {

        String secret =
                Base64.getEncoder()
                        .encodeToString(
                                new byte[32]
                        );

        StandardEnvironment environment =
                new StandardEnvironment();

        environment.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "safeai-test-env",
                                Map.of(
                                        "SAFEAI_JWT_SECRET",
                                        secret,
                                        "SAFEAI_JWT_EXPIRATION_MINUTES",
                                        "17",
                                        "SAFEAI_JWT_ISSUER",
                                        LOCAL_ISSUER,
                                        "SAFEAI_JWT_AUDIENCE",
                                        "test-audience"
                                )
                        )
                );

        YamlPropertySourceLoader loader =
                new YamlPropertySourceLoader();

        List<PropertySource<?>> sources =
                loader.load(
                        "application",
                        new ClassPathResource(
                                "application.yaml"
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
                properties.secret()
        ).isEqualTo(
                secret
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
                properties.secretKey()
                        .getEncoded()
        ).hasSize(
                32
        );
    }
}