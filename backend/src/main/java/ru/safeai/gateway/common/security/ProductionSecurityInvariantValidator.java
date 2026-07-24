package ru.safeai.gateway.common.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Fail-fast проверки, относящиеся именно к выбранной production-модели:
 * TLS завершается на Nginx, forwarded headers обрабатывает ClientIpResolver.
 */
@Component
@Profile({"prod", "production"})
public class ProductionSecurityInvariantValidator
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
        this.corsProperties = corsProperties;
        this.clientIpProperties = clientIpProperties;
        this.jwtProperties = jwtProperties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (corsProperties.allowedOrigins().isEmpty()) {
            throw new IllegalStateException(
                    "В production должен быть задан хотя бы один CORS origin"
            );
        }

        if (corsProperties.allowedOrigins().stream()
                .anyMatch(origin -> !origin.startsWith("https://"))) {
            throw new IllegalStateException(
                    "В prod все safeai.cors.allowed-origins должны использовать HTTPS"
            );
        }

        if (clientIpProperties.trustedProxyCidrs().isEmpty()) {
            throw new IllegalStateException(
                    "В production должен быть задан trusted CIDR для edge proxy"
            );
        }
        if (clientIpProperties.trustedProxyCidrs().stream().anyMatch(cidr ->
                "0.0.0.0/0".equals(cidr) || "::/0".equals(cidr))) {
            throw new IllegalStateException(
                    "Нельзя доверять всем IP-адресам как reverse proxy"
            );
        }

        if (!jwtProperties.issuer().startsWith("https://")) {
            throw new IllegalStateException(
                    "В prod app.security.jwt.issuer должен использовать HTTPS"
            );
        }

        String strategy = environment.getProperty(
                "server.forward-headers-strategy",
                "none"
        );
        if (!"none".equals(strategy.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "При использовании ClientIpResolver установите "
                            + "server.forward-headers-strategy=none"
            );
        }
    }
}
