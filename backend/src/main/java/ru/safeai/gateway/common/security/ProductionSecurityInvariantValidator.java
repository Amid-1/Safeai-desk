package ru.safeai.gateway.common.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Fail-fast проверки production security model.
 */
@Component
@Profile({"prod", "production"})
public final class ProductionSecurityInvariantValidator
        implements SmartInitializingSingleton {

    private final CorsProperties corsProperties;
    private final ClientIpProperties clientIpProperties;
    private final JwtProperties jwtProperties;
    private final Environment environment;

    public ProductionSecurityInvariantValidator(
            CorsProperties corsProperties,
            ClientIpProperties clientIpProperties,
            JwtProperties jwtProperties,
            Environment environment
    ) {
        this.corsProperties =
                Objects.requireNonNull(
                        corsProperties,
                        "corsProperties не должен быть null"
                );

        this.clientIpProperties =
                Objects.requireNonNull(
                        clientIpProperties,
                        "clientIpProperties не должен быть null"
                );

        this.jwtProperties =
                Objects.requireNonNull(
                        jwtProperties,
                        "jwtProperties не должен быть null"
                );

        this.environment =
                Objects.requireNonNull(
                        environment,
                        "environment не должен быть null"
                );
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateCors();
        validateTrustedProxies();
        validateIssuer();
        validateForwardedHeadersStrategy();
        validateManagementServer();
    }

    private void validateCors() {
        if (corsProperties
                .allowedOrigins()
                .isEmpty()) {
            throw new IllegalStateException(
                    "В production должен быть задан хотя бы один CORS origin"
            );
        }

        if (corsProperties
                .allowedOrigins()
                .stream()
                .anyMatch(origin ->
                        !origin.startsWith(
                                "https://"
                        )
                )) {
            throw new IllegalStateException(
                    "В prod все safeai.cors.allowed-origins "
                            + "должны использовать HTTPS"
            );
        }
    }

    private void validateTrustedProxies() {
        if (clientIpProperties
                .trustedProxyCidrs()
                .isEmpty()) {
            throw new IllegalStateException(
                    "В production должен быть задан trusted CIDR "
                            + "для edge proxy"
            );
        }

        boolean trustAll =
                clientIpProperties
                        .trustedProxyCidrs()
                        .stream()
                        .anyMatch(cidr ->
                                "0.0.0.0/0".equals(cidr)
                                        || "::/0".equals(cidr)
                        );

        if (trustAll) {
            throw new IllegalStateException(
                    "Нельзя доверять всем IP-адресам как reverse proxy"
            );
        }
    }

    private void validateIssuer() {
        URI issuer =
                URI.create(
                        jwtProperties.issuer()
                );

        if (!"https".equalsIgnoreCase(
                issuer.getScheme()
        )) {
            throw new IllegalStateException(
                    "В prod app.security.jwt.issuer "
                            + "должен использовать HTTPS"
            );
        }
    }

    private void validateForwardedHeadersStrategy() {
        String strategy =
                environment.getProperty(
                        "server.forward-headers-strategy",
                        "none"
                );

        if (!"none".equals(
                strategy.toLowerCase(
                        Locale.ROOT
                )
        )) {
            throw new IllegalStateException(
                    "При использовании ClientIpResolver установите "
                            + "server.forward-headers-strategy=none"
            );
        }
    }

    private void validateManagementServer() {
        Integer appPort =
                environment.getProperty(
                        "server.port",
                        Integer.class,
                        8080
                );

        Integer managementPort =
                environment.getProperty(
                        "management.server.port",
                        Integer.class
                );

        String managementAddress =
                environment.getProperty(
                        "management.server.address"
                );

        if (managementPort == null) {
            throw new IllegalStateException(
                    "В production management.server.port "
                            + "должен быть задан явно"
            );
        }

        if (managementPort < 1
                || managementPort > 65_535) {
            throw new IllegalStateException(
                    "В production management.server.port "
                            + "должен быть в диапазоне 1..65535"
            );
        }

        if (managementPort.equals(appPort)) {
            throw new IllegalStateException(
                    "В production management.server.port "
                            + "должен отличаться от server.port"
            );
        }

        if (managementAddress == null
                || managementAddress.isBlank()) {
            throw new IllegalStateException(
                    "В production management.server.address "
                            + "должен быть задан явно"
            );
        }

        String normalizedAddress =
                managementAddress.trim();

        /*
         * Архитектурный invariant проекта — management contour
         * привязан к конкретному internal/ops interface, а не ко всем
         * интерфейсам хоста.
         */
        if ("0.0.0.0".equals(normalizedAddress)
                || "::".equals(normalizedAddress)
                || "0:0:0:0:0:0:0:0".equals(normalizedAddress)) {
            throw new IllegalStateException(
                    "В production management.server.address "
                            + "не должен быть wildcard address"
            );
        }
    }
}
