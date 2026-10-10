package ru.safeai.gateway.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * Production password encoder.
 *
 * <p>New hashes always use {@code {bcrypt}} and BCrypt cost 12.</p>
 *
 * <p>Legacy unprefixed BCrypt matching is an explicit migration compatibility
 * switch. Keep it enabled only while legacy rows remain; after migration set
 * {@code safeai.security.password.allow-legacy-unprefixed-bcrypt=false}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncodingConfiguration {

    private static final String BCRYPT_ID =
            "bcrypt";

    private static final int BCRYPT_STRENGTH =
            12;

    /*
     * Field default keeps direct unit construction backward-compatible.
     * Spring overrides it from configuration when the bean is managed.
     */
    @Value(
            "${safeai.security.password.allow-legacy-unprefixed-bcrypt:true}"
    )
    private boolean allowLegacyUnprefixedBcrypt =
            true;

    @Bean
    PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt =
                new BCryptPasswordEncoder(
                        BCRYPT_STRENGTH
                );

        DelegatingPasswordEncoder delegating =
                new DelegatingPasswordEncoder(
                        BCRYPT_ID,
                        Map.of(
                                BCRYPT_ID,
                                bcrypt
                        )
                );

        if (allowLegacyUnprefixedBcrypt) {
            delegating
                    .setDefaultPasswordEncoderForMatches(
                            bcrypt
                    );
        }

        return delegating;
    }
}
