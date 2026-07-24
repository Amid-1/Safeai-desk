package ru.safeai.gateway.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * Production-конфигурация кодирования паролей.
 *
 * <p>Новые пароли получают префикс {@code {bcrypt}}
 * и кодируются BCrypt с cost factor 12.</p>
 *
 * <p>Старые BCrypt-хеши без префикса продолжают
 * проверяться через fallback encoder.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncodingConfiguration {

    private static final String BCRYPT_ID = "bcrypt";
    private static final int BCRYPT_STRENGTH = 12;

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

        delegating.setDefaultPasswordEncoderForMatches(
                bcrypt
        );

        return delegating;
    }
}