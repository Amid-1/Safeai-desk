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
 * <p>{@link ProductionSecurityInvariantValidator} proves only the application
 * side of the boundary: a dedicated management port bound to a loopback/private
 * interface. Production infrastructure must independently enforce firewall /
 * security-group / Kubernetes NetworkPolicy (or an equivalent mTLS/auth
 * boundary) so this port is not reachable from the public application
 * network.</p>
 *
 * <p>With that two-layer boundary in place, health/info/prometheus may remain
 * unauthenticated for internal probes/scrapers. All other Actuator endpoints
 * are denied.</p>
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
