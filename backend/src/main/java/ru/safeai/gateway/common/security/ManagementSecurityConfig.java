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
 * <p>Production binds management.server.address to the dedicated internal
 * monitoring interface. Prometheus/health/info are therefore unauthenticated
 * only inside that network boundary. The public application SecurityConfig
 * remains fail-closed for /actuator/**.</p>
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
