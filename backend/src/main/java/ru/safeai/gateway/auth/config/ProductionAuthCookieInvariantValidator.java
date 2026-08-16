package ru.safeai.gateway.auth.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.auth.service.AuthCookieProperties;

import java.util.Objects;

/**
 * Fail-fast production invariant for browser authentication cookies.
 *
 * <p>Located in the auth module intentionally: common.security must not
 * depend on auth.service just to validate cookie configuration.</p>
 */
@Component
@Profile({"prod", "production"})
public final class ProductionAuthCookieInvariantValidator
        implements SmartInitializingSingleton {

    private final AuthCookieProperties authCookieProperties;

    public ProductionAuthCookieInvariantValidator(
            AuthCookieProperties authCookieProperties
    ) {
        this.authCookieProperties = Objects.requireNonNull(
                authCookieProperties,
                "authCookieProperties не должен быть null"
        );
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!authCookieProperties.secure()) {
            throw new IllegalStateException(
                    "В production safeai.auth.cookies.secure должен быть true"
            );
        }
    }
}
