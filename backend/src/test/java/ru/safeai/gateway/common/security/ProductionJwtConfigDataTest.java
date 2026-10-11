package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionJwtConfigDataTest {

    @TempDir
    Path tempDir;

    @Test
    void externalJwtKeyRingOverridesApplicationYml()
            throws IOException {

        Path keyRing = tempDir.resolve("jwt-keys.yml");

        Files.writeString(
                keyRing,
                """
                app:
                  security:
                    jwt:
                      active-key-id: prod-test-key
                      keys:
                        - id: prod-test-key
                          public-key: |-
                            -----BEGIN PUBLIC KEY-----
                            TEST_ONLY_PUBLIC_KEY
                            -----END PUBLIC KEY-----
                          private-key: |-
                            -----BEGIN PRIVATE KEY-----
                            TEST_ONLY_PRIVATE_KEY
                            -----END PRIVATE KEY-----
                """,
                StandardCharsets.UTF_8
        );

        SpringApplication application =
                new SpringApplication(MinimalApplication.class);

        application.setWebApplicationType(
                WebApplicationType.NONE
        );

        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);

        try (ConfigurableApplicationContext context =
                     application.run(
                             "--spring.profiles.active=prod",
                             "--spring.config.additional-location="
                                     + keyRing.toUri(),
                             "--SAFEAI_JWT_ISSUER="
                                     + "https://safeai.example.test"
                     )) {

            JwtProperties properties =
                    Binder.get(context.getEnvironment())
                            .bind(
                                    "app.security.jwt",
                                    JwtProperties.class
                            )
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Production JWT configuration was not bound"
                                    )
                            );

            assertThat(properties.activeKeyId())
                    .isEqualTo("prod-test-key");

            assertThat(properties.issuer())
                    .isEqualTo("https://safeai.example.test");

            assertThat(properties.keys()).hasSize(1);

            JwtProperties.KeyEntry activeKey =
                    properties.keys().getFirst();

            assertThat(activeKey.id())
                    .isEqualTo("prod-test-key");

            assertThat(activeKey.publicKey())
                    .contains("TEST_ONLY_PUBLIC_KEY");

            assertThat(activeKey.privateKey())
                    .contains("TEST_ONLY_PRIVATE_KEY");
        }
    }

    @SpringBootConfiguration
    static class MinimalApplication {
    }
}
