package ru.safeai.gateway.common.security;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security chain for the dedicated Actuator management server.
 *
 * <p>В production отдельные {@code management.server.port} и
 * {@code management.server.address} являются fail-fast invariant,
 * проверяемым {@link ProductionSecurityInvariantValidator}. Поэтому
 * Prometheus/health/info остаются unauthenticated только внутри выделенного
 * management contour. Application security chain не используется как
 * единственная граница защиты Actuator.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ManagementSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain managementSecurityFilterChain(
            HttpSecurity http
    ) {
        http
                .securityMatcher(
                        EndpointRequest.toAnyEndpoint()
                )
                .authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers(
                                        EndpointRequest.to(
                                                "health",
                                                "info",
                                                "prometheus"
                                        )
                                )
                                .permitAll()
                                .anyRequest()
                                .denyAll()
                )
                .csrf(
                        AbstractHttpConfigurer::disable
                )
                .requestCache(
                        AbstractHttpConfigurer::disable
                )
                .formLogin(
                        AbstractHttpConfigurer::disable
                )
                .httpBasic(
                        AbstractHttpConfigurer::disable
                )
                .logout(
                        AbstractHttpConfigurer::disable
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                );

        return http.build();
    }
}




