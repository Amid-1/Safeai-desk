package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityInvariantValidatorTest {

    private static final String ACTIVE_KEY_ID =
            "test-active-key";

    private static final String PREVIOUS_KEY_ID =
            "test-previous-key";

    private static final String APPLICATION_PORT =
            "8080";

    private static final String MANAGEMENT_PORT =
            "9091";

    private static final String MANAGEMENT_ADDRESS =
            "172.31.0.20";

    private static final String TEST_PUBLIC_KEY =
            String.join(
                    "\n",
                    "-----BEGIN PUBLIC KEY-----",
                    "test-public-key",
                    "-----END PUBLIC KEY-----"
            );

    private static final String TEST_PRIVATE_KEY =
            String.join(
                    "\n",
                    "-----BEGIN PRIVATE KEY-----",
                    "test-private-key",
                    "-----END PRIVATE KEY-----"
            );

    @Test
    void nonUriIssuerFailsBeforeProductionInvariantValidation() {
        assertThatThrownBy(() ->
                properties(
                        "safeai-desk"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );
    }

    @Test
    void prodHttpIssuerFailsSecurityInvariant() {
        ProductionSecurityInvariantValidator validator =
                validator(
                        insecureHttpUrl(
                                "safeai.example.com"
                        ),
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        "none"
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "issuer"
                )
                .hasMessageContaining(
                        "HTTPS"
                );
    }

    @Test
    void prodHttpsIssuerPassesSecurityInvariant() {
        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        "none"
                );

        assertThatCode(
                validator
                        ::afterSingletonsInstantiated
        ).doesNotThrowAnyException();
    }

    @Test
    void prodRequiresAtLeastOneCorsOrigin() {
        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        "none"
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "CORS"
                );
    }

    @Test
    void prodRejectsHttpCorsOrigin() {
        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                insecureHttpUrl(
                                        "app.example.com"
                                )
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        "none"
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "HTTPS"
                );
    }

    @Test
    void prodRequiresTrustedProxyCidr() {
        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(),
                        "none"
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "trusted CIDR"
                );
    }

    @Test
    void prodRejectsTrustAllIpv4ProxyRange() {
        assertTrustAllRejected(
                "0.0.0.0/0"
        );
    }

    @Test
    void prodRejectsTrustAllIpv6ProxyRange() {
        assertTrustAllRejected(
                "::/0"
        );
    }

    @Test
    void prodRequiresForwardHeadersStrategyNone() {
        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        "framework"
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "forward-headers-strategy=none"
                );
    }

    @Test
    void prodRequiresExplicitManagementPort() {
        MockEnvironment environment =
                validProductionEnvironment();

        environment.setProperty(
                "management.server.port",
                ""
        );

        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        environment
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        RuntimeException.class
                )
                .hasMessageContaining(
                        "management.server.port"
                );
    }

    @Test
    void prodRejectsManagementPortEqualToApplicationPort() {
        MockEnvironment environment =
                validProductionEnvironment()
                        .withProperty(
                                "management.server.port",
                                APPLICATION_PORT
                        );

        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        environment
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "management.server.port"
                );
    }

    @Test
    void prodRequiresExplicitManagementAddress() {
        MockEnvironment environment =
                validProductionEnvironment();

        environment.setProperty(
                "management.server.address",
                ""
        );

        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        environment
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "management.server.address"
                );
    }

    @Test
    void prodRejectsWildcardIpv4ManagementAddress() {
        assertManagementAddressRejected(
                "0.0.0.0"
        );
    }

    @Test
    void prodRejectsWildcardIpv6ManagementAddress() {
        assertManagementAddressRejected(
                "::"
        );
    }

    @Test
    void prodRejectsPublicManagementAddress() {
        assertManagementAddressRejected(
                "203.0.113.10"
        );
    }

    @Test
    void prodRejectsPreviousJwtPrivateKeyMaterial() {
        ProductionSecurityInvariantValidator validator =
                new ProductionSecurityInvariantValidator(
                        new CorsProperties(
                                List.of(
                                        "https://app.example.com"
                                )
                        ),
                        new ClientIpProperties(
                                List.of(
                                        "172.28.0.0/24"
                                )
                        ),
                        propertiesWithPreviousPrivateKey(
                                "https://safeai.example.com"
                        ),
                        validProductionEnvironment()
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        PREVIOUS_KEY_ID
                )
                .hasMessageContaining(
                        "private-key"
                );
    }

    @Test
    void prodRequiresSchedulerPoolOfAtLeastTwo() {
        MockEnvironment environment =
                validProductionEnvironment()
                        .withProperty(
                                "spring.task.scheduling.pool.size",
                                "1"
                        );

        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        environment
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "spring.task.scheduling.pool.size"
                );
    }

    @Test
    void prodRequiresExplicitLegacyBcryptDecision() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty(
                                "safeai.environment",
                                "production"
                        )
                        .withProperty(
                                "server.port",
                                APPLICATION_PORT
                        )
                        .withProperty(
                                "management.server.port",
                                MANAGEMENT_PORT
                        )
                        .withProperty(
                                "management.server.address",
                                MANAGEMENT_ADDRESS
                        )
                        .withProperty(
                                "server.forward-headers-strategy",
                                "none"
                        )
                        .withProperty(
                                "spring.task.scheduling.pool.size",
                                "4"
                        );

        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        environment
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "allow-legacy-unprefixed-bcrypt"
                );
    }

    private void assertTrustAllRejected(
            String cidr
    ) {
        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                cidr
                        ),
                        "none"
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "Нельзя доверять всем"
                );
    }

    private void assertManagementAddressRejected(
            String address
    ) {
        MockEnvironment environment =
                validProductionEnvironment()
                        .withProperty(
                                "management.server.address",
                                address
                        );

        ProductionSecurityInvariantValidator validator =
                validator(
                        "https://safeai.example.com",
                        List.of(
                                "https://app.example.com"
                        ),
                        List.of(
                                "172.28.0.0/24"
                        ),
                        environment
                );

        assertThatThrownBy(
                validator
                        ::afterSingletonsInstantiated
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "management.server.address"
                );
    }

    private ProductionSecurityInvariantValidator validator(
            String issuer,
            List<String> origins,
            List<String> proxyCidrs,
            String forwardStrategy
    ) {
        MockEnvironment environment =
                validProductionEnvironment()
                        .withProperty(
                                "server.forward-headers-strategy",
                                forwardStrategy
                        );

        return validator(
                issuer,
                origins,
                proxyCidrs,
                environment
        );
    }

    private ProductionSecurityInvariantValidator validator(
            String issuer,
            List<String> origins,
            List<String> proxyCidrs,
            MockEnvironment environment
    ) {
        return new ProductionSecurityInvariantValidator(
                new CorsProperties(
                        origins
                ),
                new ClientIpProperties(
                        proxyCidrs
                ),
                properties(
                        issuer
                ),
                environment
        );
    }

    private MockEnvironment validProductionEnvironment() {
        return new MockEnvironment()
                .withProperty(
                        "safeai.environment",
                        "production"
                )
                .withProperty(
                        "server.port",
                        APPLICATION_PORT
                )
                .withProperty(
                        "management.server.port",
                        MANAGEMENT_PORT
                )
                .withProperty(
                        "management.server.address",
                        MANAGEMENT_ADDRESS
                )
                .withProperty(
                        "server.forward-headers-strategy",
                        "none"
                )
                .withProperty(
                        "spring.task.scheduling.pool.size",
                        "4"
                )
                .withProperty(
                        "safeai.security.password.allow-legacy-unprefixed-bcrypt",
                        "true"
                );
    }

    private JwtProperties properties(
            String issuer
    ) {
        return new JwtProperties(
                15L,
                issuer,
                "safeai-desk-api",
                ACTIVE_KEY_ID,
                List.of(
                        new JwtProperties.KeyEntry(
                                ACTIVE_KEY_ID,
                                TEST_PUBLIC_KEY,
                                TEST_PRIVATE_KEY
                        )
                )
        );
    }

    private JwtProperties propertiesWithPreviousPrivateKey(
            String issuer
    ) {
        return new JwtProperties(
                15L,
                issuer,
                "safeai-desk-api",
                ACTIVE_KEY_ID,
                List.of(
                        new JwtProperties.KeyEntry(
                                ACTIVE_KEY_ID,
                                TEST_PUBLIC_KEY,
                                TEST_PRIVATE_KEY
                        ),
                        new JwtProperties.KeyEntry(
                                PREVIOUS_KEY_ID,
                                TEST_PUBLIC_KEY,
                                TEST_PRIVATE_KEY
                        )
                )
        );
    }

    private static String insecureHttpUrl(
            String authority
    ) {
        return String.join(
                "",
                "http",
                "://",
                authority
        );
    }
}
