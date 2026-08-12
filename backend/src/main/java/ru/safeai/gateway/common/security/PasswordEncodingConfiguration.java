package ru.safeai.gateway.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * Production password encoder.
 *
 * <p>New hashes use `{bcrypt}` and BCrypt cost 12.
 * Legacy unprefixed BCrypt hashes remain readable during migration.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncodingConfiguration {

    private static final String BCRYPT_ID =
            "bcrypt";

    private static final int BCRYPT_STRENGTH =
            12;

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

        delegating
                .setDefaultPasswordEncoderForMatches(
                        bcrypt
                );

        return delegating;
    }
}
