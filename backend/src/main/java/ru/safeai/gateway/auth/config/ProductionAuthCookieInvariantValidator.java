package ru.safeai.gateway.auth.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.auth.security.HstsProperties;
import ru.safeai.gateway.auth.service.AuthCookieProperties;
import ru.safeai.gateway.common.security.ProductionEnvironmentCondition;

import java.util.Objects;

/**
 * Fail-fast production invariant for browser authentication cookies.
 *
 * <p>The production browser-auth boundary is host-only. Domain-scoped auth or
 * CSRF cookies are intentionally rejected because they allow sibling
 * subdomains to participate in cookie scope. Cross-subdomain authentication
 * requires a different explicitly reviewed design.</p>
 */
@Component
@Conditional(ProductionEnvironmentCondition.class)
public final class ProductionAuthCookieInvariantValidator
        implements SmartInitializingSingleton {

    private final AuthCookieProperties authCookieProperties;
    private final HstsProperties hstsProperties;

    public ProductionAuthCookieInvariantValidator(
            AuthCookieProperties authCookieProperties,
            HstsProperties hstsProperties
    ) {
        this.authCookieProperties =
                Objects.requireNonNull(
                        authCookieProperties,
                        "authCookieProperties не должен быть null"
                );

        this.hstsProperties =
                Objects.requireNonNull(
                        hstsProperties,
                        "hstsProperties не должен быть null"
                );
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateSecureCookies();
        validateHostOnlyCookieBoundary();
        validateHstsGuarantee();
    }

    private void validateSecureCookies() {
        if (!authCookieProperties.secure()) {
            throw new IllegalStateException(
                    "В production safeai.auth.cookies.secure "
                            + "должен быть true"
            );
        }
    }

    private void validateHostOnlyCookieBoundary() {
        if (authCookieProperties.hasDomain()) {
            throw new IllegalStateException(
                    "В production safeai.auth.cookies.domain "
                            + "должен отсутствовать: auth/refresh/CSRF cookies "
                            + "должны быть host-only"
            );
        }

        if (!authCookieProperties
                .accessTokenName()
                .startsWith(
                        "__Host-"
                )) {
            throw new IllegalStateException(
                    "В production access-token cookie "
                            + "должна использовать __Host- prefix"
            );
        }

        if (!authCookieProperties
                .refreshTokenName()
                .startsWith(
                        "__Host-"
                )) {
            throw new IllegalStateException(
                    "В production refresh-token cookie "
                            + "должна использовать __Host- prefix"
            );
        }

        if (!authCookieProperties
                .csrfTokenName()
                .startsWith(
                        "__Host-"
                )) {
            throw new IllegalStateException(
                    "В production CSRF cookie "
                            + "должна использовать __Host- prefix"
            );
        }
    }

    private void validateHstsGuarantee() {
        if (!hstsProperties.enabled()
                && !hstsProperties.edgeManaged()) {
            throw new IllegalStateException(
                    "В production HSTS должен быть гарантирован: "
                            + "либо safeai.security.hsts.enabled=true "
                            + "в приложении, либо "
                            + "safeai.security.hsts.edge-managed=true "
                            + "для trusted TLS edge"
            );
        }
    }
}
