package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityInvariantValidatorTest {

    private static final String ACTIVE_KEY_ID =
            "test-active-key";

    /*
     * В этом тесте криптографический материал не используется:
     * ProductionSecurityInvariantValidator проверяет production-инварианты
     * issuer/CORS/proxy/forward headers. Для JwtProperties достаточно
     * корректно заполненного key-ring контракта.
     *
     * String.join используется намеренно, чтобы тестовые PEM-строки
     * не содержали завершающий перевод строки. JwtProperties запрещает
     * внешний whitespace у public/private key.
     */
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

    private ProductionSecurityInvariantValidator validator(
            String issuer,
            List<String> origins,
            List<String> proxyCidrs,
            String forwardStrategy
    ) {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty(
                                "server.forward-headers-strategy",
                                forwardStrategy
                        );

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