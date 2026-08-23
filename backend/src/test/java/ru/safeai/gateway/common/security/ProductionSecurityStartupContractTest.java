package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSecurityStartupContractTest {

    private static final String ACTIVE_KEY_ID =
            "test-active-key";

    private static final String APPLICATION_PORT =
            "8080";

    private static final String MANAGEMENT_PORT =
            "9091";

    private static final String MANAGEMENT_ADDRESS =
            "172.31.0.20";

    private static final KeyPair RSA_KEY_PAIR =
            generateRsaKeyPair();

    private static final String PUBLIC_KEY =
            toPublicKeyPem();

    private static final String PRIVATE_KEY =
            toPrivateKeyPem();

    /**
     * Полностью валидный production baseline.
     *
     * <p>Каждый отдельный startup test изменяет только один
     * production security invariant.</p>
     */
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
                            /*
                             * JWT key-ring contract.
                             */
                            "app.security.jwt.expiration-minutes=15",

                            "app.security.jwt.audience="
                                    + "safeai-desk-api",

                            "app.security.jwt.active-key-id="
                                    + ACTIVE_KEY_ID,

                            "app.security.jwt.keys[0].id="
                                    + ACTIVE_KEY_ID,

                            "app.security.jwt.keys[0].public-key="
                                    + PUBLIC_KEY,

                            "app.security.jwt.keys[0].private-key="
                                    + PRIVATE_KEY,

                            /*
                             * Production CORS boundary.
                             */
                            "safeai.cors.allowed-origins[0]="
                                    + "https://app.example.com",

                            /*
                             * Production trusted edge proxy.
                             */
                            "safeai.security.client-ip."
                                    + "trusted-proxy-cidrs[0]="
                                    + "172.28.0.0/24",

                            /*
                             * Forwarded headers обрабатываются только
                             * собственным trusted-proxy pipeline.
                             */
                            "server.forward-headers-strategy=none",

                            /*
                             * Public application server.
                             */
                            "server.port="
                                    + APPLICATION_PORT,

                            /*
                             * Dedicated internal Actuator server.
                             *
                             * Его port обязан:
                             * - быть задан явно;
                             * - отличаться от application server port.
                             *
                             * Его address обязан:
                             * - быть задан явно;
                             * - не быть wildcard.
                             */
                            "management.server.port="
                                    + MANAGEMENT_PORT,

                            "management.server.address="
                                    + MANAGEMENT_ADDRESS
                    );

    @Test
    void prodWithNonUriIssuerFailsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.security.jwt.issuer="
                                + "safeai-desk"
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
                        "app.security.jwt.issuer="
                                + insecureHttpIssuer()
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
                        "app.security.jwt.issuer="
                                + "https://safeai.example.com"
                )
                .run(context ->
                        assertThat(context)
                                .hasNotFailed()
                );
    }

    @Test
    void prodWithoutManagementPortFailsStartup() {
        ApplicationContextRunner runner =
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

                                "app.security.jwt.issuer="
                                        + "https://safeai.example.com",

                                "app.security.jwt.audience="
                                        + "safeai-desk-api",

                                "app.security.jwt.active-key-id="
                                        + ACTIVE_KEY_ID,

                                "app.security.jwt.keys[0].id="
                                        + ACTIVE_KEY_ID,

                                "app.security.jwt.keys[0].public-key="
                                        + PUBLIC_KEY,

                                "app.security.jwt.keys[0].private-key="
                                        + PRIVATE_KEY,

                                "safeai.cors.allowed-origins[0]="
                                        + "https://app.example.com",

                                "safeai.security.client-ip."
                                        + "trusted-proxy-cidrs[0]="
                                        + "172.28.0.0/24",

                                "server.forward-headers-strategy=none",

                                "server.port="
                                        + APPLICATION_PORT,

                                /*
                                 * management.server.port
                                 * намеренно отсутствует.
                                 */
                                "management.server.address="
                                        + MANAGEMENT_ADDRESS
                        );

        runner.run(context ->
                assertThat(context)
                        .hasFailed()
        );
    }

    @Test
    void prodWithManagementPortEqualToApplicationPortFailsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.security.jwt.issuer="
                                + "https://safeai.example.com",

                        "management.server.port="
                                + APPLICATION_PORT
                )
                .run(context ->
                        assertThat(context)
                                .hasFailed()
                );
    }

    @Test
    void prodWithWildcardManagementAddressFailsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.security.jwt.issuer="
                                + "https://safeai.example.com",

                        "management.server.address="
                                + "0.0.0.0"
                )
                .run(context ->
                        assertThat(context)
                                .hasFailed()
                );
    }

    private static String insecureHttpIssuer() {
        return String.join(
                "",
                "http",
                "://",
                "safeai.example.com"
        );
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance(
                            "RSA"
                    );

            generator.initialize(
                    2048
            );

            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Не удалось создать RSA key pair для теста",
                    exception
            );
        }
    }

    private static String toPublicKeyPem() {
        return toPem(
                "PUBLIC KEY",
                RSA_KEY_PAIR
                        .getPublic()
                        .getEncoded()
        );
    }

    private static String toPrivateKeyPem() {
        return toPem(
                "PRIVATE KEY",
                RSA_KEY_PAIR
                        .getPrivate()
                        .getEncoded()
        );
    }

    private static String toPem(
            String type,
            byte[] encoded
    ) {
        String body =
                Base64.getMimeEncoder(
                                64,
                                "\n".getBytes(
                                        StandardCharsets.US_ASCII
                                )
                        )
                        .encodeToString(
                                encoded
                        );

        return "-----BEGIN "
                + type
                + "-----\n"
                + body
                + "\n-----END "
                + type
                + "-----";
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