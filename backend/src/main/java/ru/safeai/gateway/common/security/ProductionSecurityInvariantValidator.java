package ru.safeai.gateway.common.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

/**
 * Fail-fast проверки production security/operations model.
 *
 * <p>The bean is selected by {@link ProductionEnvironmentCondition}; it does
 * not depend on an exact {@code prod}/{@code production} profile name.</p>
 */
@Component
@Conditional(ProductionEnvironmentCondition.class)
public final class ProductionSecurityInvariantValidator
        implements SmartInitializingSingleton {

    private static final int MIN_SCHEDULER_POOL_SIZE =
            2;

    private static final String LEGACY_BCRYPT_PROPERTY =
            "safeai.security.password.allow-legacy-unprefixed-bcrypt";

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
        validateJwtRotationKeyMaterial();
        validateSchedulerPool();
        validateLegacyPasswordCompatibilityDeclaration();
        validateHsts();
    }

    /**
     * A production deployment must enforce HSTS either in Spring Security
     * or at its trusted HTTPS termination boundary.
     *
     * Edge-managed mode is a deployment declaration. The actual public
     * response header must also be checked during deployment smoke tests.
     */
    private void validateHsts() {
        boolean applicationManaged = environment.getProperty(
                "safeai.security.hsts.enabled",
                Boolean.class,
                false
        );

        boolean edgeManaged = environment.getProperty(
                "safeai.security.hsts.edge-managed",
                Boolean.class,
                false
        );

        if (!applicationManaged && !edgeManaged) {
            throw new IllegalStateException(
                    "В production требуется HSTS: включите "
                            + "safeai.security.hsts.enabled либо "
                            + "safeai.security.hsts.edge-managed"
            );
        }
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

        if (managementPort.equals(
                appPort
        )) {
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

        InetAddress resolvedAddress =
                resolveManagementAddress(
                        normalizedAddress
                );

        if (!isInternalManagementAddress(
                resolvedAddress
        )) {
            throw new IllegalStateException(
                    "В production management.server.address "
                            + "должен быть loopback/private/link-local "
                            + "адресом внутреннего management contour, "
                            + "а не public-facing interface: "
                            + normalizedAddress
            );
        }
    }

    private InetAddress resolveManagementAddress(
            String address
    ) {
        try {
            return InetAddress.getByName(
                    address
            );
        } catch (UnknownHostException exception) {
            throw new IllegalStateException(
                    "В production management.server.address "
                            + "не удалось разрешить: "
                            + address,
                    exception
            );
        }
    }

    private boolean isInternalManagementAddress(
            InetAddress address
    ) {
        if (address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || isIpv6UniqueLocalAddress(
                address
        );
    }

    private boolean isIpv6UniqueLocalAddress(
            InetAddress address
    ) {
        byte[] bytes =
                address.getAddress();

        return bytes.length == 16
                && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * Only the active JWT key may carry signing material in production.
     */
    private void validateJwtRotationKeyMaterial() {
        for (JwtProperties.KeyEntry key
                : jwtProperties.keys()) {

            if (!key.id().equals(
                    jwtProperties.activeKeyId()
            )
                    && key.privateKey() != null) {

                throw new IllegalStateException(
                        "В production previous JWT key "
                                + key.id()
                                + " не должен содержать private-key; "
                                + "оставьте только public-key"
                );
            }
        }
    }

    /**
     * A single scheduler thread lets one slow maintenance task starve unrelated
     * jobs. Production must declare an explicit pool of at least two threads.
     */
    private void validateSchedulerPool() {
        Integer schedulerPoolSize =
                environment.getProperty(
                        "spring.task.scheduling.pool.size",
                        Integer.class
                );

        if (schedulerPoolSize == null
                || schedulerPoolSize < MIN_SCHEDULER_POOL_SIZE) {

            throw new IllegalStateException(
                    "В production spring.task.scheduling.pool.size "
                            + "должен быть задан явно и быть >= "
                            + MIN_SCHEDULER_POOL_SIZE
            );
        }
    }

    /**
     * Legacy unprefixed BCrypt is a migration switch, not an implicit permanent
     * behavior. Production must make the choice explicit.
     */
    private void validateLegacyPasswordCompatibilityDeclaration() {
        Boolean allowLegacy =
                environment.getProperty(
                        LEGACY_BCRYPT_PROPERTY,
                        Boolean.class
                );

        if (allowLegacy == null) {
            throw new IllegalStateException(
                    "В production "
                            + LEGACY_BCRYPT_PROPERTY
                            + " должен быть задан явно. "
                            + "Используйте true только на период миграции "
                            + "legacy BCrypt hashes, затем переключите на false"
            );
        }
    }
}
