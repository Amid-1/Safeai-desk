package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSecurityStartupContractTest {

    private static final String VALID_SECRET =
            Base64.getEncoder()
                    .encodeToString(
                            new byte[32]
                    );

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(context ->
                            context.getEnvironment()
                                    .setActiveProfiles(
                                            "prod"
                                    )
                    )
                    .withUserConfiguration(
                            SecurityPropertiesConfiguration.class,
                            TestConfiguration.class
                    )
                    .withPropertyValues(
                            "app.security.jwt.expiration-minutes=15",
                            "app.security.jwt.audience=safeai-desk-api",
                            "safeai.cors.allowed-origins[0]=https://app.example.com",
                            "safeai.security.client-ip.trusted-proxy-cidrs[0]=172.28.0.0/24",
                            "server.forward-headers-strategy=none"
                    );

    @Test
    void prodWithNonUriIssuerFailsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.security.jwt.secret="
                                + VALID_SECRET,
                        "app.security.jwt.issuer=safeai-desk"
                )
                .run(context ->
                        assertThat(context)
                                .hasFailed()
                );
    }

    @Test
    void prodWithHttpIssuerFailsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.security.jwt.secret="
                                + VALID_SECRET,
                        "app.security.jwt.issuer="
                                + insecureHttpUrl(
                                        "safeai.example.com"
                                )
                )
                .run(context ->
                        assertThat(context)
                                .hasFailed()
                );
    }

    @Test
    void prodWithHttpsIssuerStarts() {
        contextRunner
                .withPropertyValues(
                        "app.security.jwt.secret="
                                + VALID_SECRET,
                        "app.security.jwt.issuer=https://safeai.example.com"
                )
                .run(context ->
                        assertThat(context)
                                .hasNotFailed()
                );
    }

    @Test
    void prodWithShortDecodedSecretFailsStartup() {
        String shortSecret =
                Base64.getEncoder()
                        .encodeToString(
                                new byte[31]
                        );

        contextRunner
                .withPropertyValues(
                        "app.security.jwt.secret="
                                + shortSecret,
                        "app.security.jwt.issuer=https://safeai.example.com"
                )
                .run(context ->
                        assertThat(context)
                                .hasFailed()
                );
    }

    @Test
    void prodWithNonBase64SecretFailsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.security.jwt.secret=not-base64!!!",
                        "app.security.jwt.issuer=https://safeai.example.com"
                )
                .run(context ->
                        assertThat(context)
                                .hasFailed()
                );
    }

    private static String insecureHttpUrl(
            String authority
    ) {
        return String.join("", "http", "://", authority);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        ProductionSecurityInvariantValidator
        productionSecurityInvariantValidator(
                CorsProperties corsProperties,
                ClientIpProperties clientIpProperties,
                JwtProperties jwtProperties,
                Environment environment
        ) {
            return new ProductionSecurityInvariantValidator(
                    corsProperties,
                    clientIpProperties,
                    jwtProperties,
                    environment
            );
        }
    }
}
