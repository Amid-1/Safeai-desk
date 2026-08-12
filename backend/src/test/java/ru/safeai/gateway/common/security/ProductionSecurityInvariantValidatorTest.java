package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityInvariantValidatorTest {

    private static final String SECRET =
            Base64.getEncoder()
                    .encodeToString(
                            new byte[32]
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
                        List.of(cidr),
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
                new CorsProperties(origins),
                new ClientIpProperties(
                        proxyCidrs
                ),
                properties(issuer),
                environment
        );
    }

    private JwtProperties properties(
            String issuer
    ) {
        return new JwtProperties(
                SECRET,
                15L,
                issuer,
                "safeai-desk-api"
        );
    }

    private static String insecureHttpUrl(
            String authority
    ) {
        return String.join("", "http", "://", authority);
    }
}
