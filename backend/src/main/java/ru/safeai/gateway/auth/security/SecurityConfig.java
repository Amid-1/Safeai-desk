package ru.safeai.gateway.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.safeai.gateway.auth.service.AuthCookieProperties;
import ru.safeai.gateway.auth.service.AuthCookieService;
import ru.safeai.gateway.common.security.ClientIpProperties;
import ru.safeai.gateway.common.security.CorsProperties;
import ru.safeai.gateway.common.security.JwtProperties;
import ru.safeai.gateway.common.security.RestAccessDeniedHandler;
import ru.safeai.gateway.common.security.RestAuthenticationEntryPoint;
import ru.safeai.gateway.common.security.SafeAiJwtAuthenticationConverter;

import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties({
        JwtProperties.class,
        CorsProperties.class,
        AuthCookieProperties.class,
        ClientIpProperties.class
})
@RequiredArgsConstructor
public class SecurityConfig {

    private static final Set<String> PUBLIC_AUTH_ENDPOINTS =
            Set.of(
                    "/api/auth/login",
                    "/api/auth/refresh",
                    "/api/auth/logout",
                    "/api/auth/csrf"
            );

    private static final String INVALID_ACCESS_TOKEN_MESSAGE =
            "Access token claims are invalid";

    private final SafeAiJwtAuthenticationConverter
            safeAiJwtAuthenticationConverter;

    private final RestAuthenticationEntryPoint
            authenticationEntryPoint;

    private final RestAccessDeniedHandler
            accessDeniedHandler;

    private final UserStatusFilter userStatusFilter;
    private final CorsProperties corsProperties;
    private final AuthCookieProperties authCookieProperties;

    @Value("${safeai.security.hsts.enabled:false}")
    private boolean hstsEnabled;

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            BearerTokenResolver bearerTokenResolver,
            AuthCookieService authCookieService,
            JwtDecoder jwtDecoder
    ) {
        RequestMatcher publicAuthEndpointMatcher =
                this::isPublicAuthEndpoint;

        AccessCookieAuthenticationFilter
                accessCookieAuthenticationFilter =
                new AccessCookieAuthenticationFilter(
                        authCookieService,
                        accessCookieAuthenticationManager(
                                jwtDecoder
                        ),
                        authenticationEntryPoint,
                        publicAuthEndpointMatcher
                );

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(
                                csrfTokenRepository
                        )
                        .csrfTokenRequestHandler(
                                new SpaCsrfTokenRequestHandler()
                        )
                )
                .cors(Customizer.withDefaults())
                .headers(this::configureHeaders)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                authenticationEntryPoint
                        )
                        .accessDeniedHandler(
                                accessDeniedHandler
                        )
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/**"
                        )
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/csrf"
                        )
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout"
                        )
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/me"
                        )
                        .authenticated()

                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**"
                        )
                        .permitAll()

                        .requestMatchers("/actuator/**")
                        .hasRole("SUPER_ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/organizations"
                        )
                        .hasRole("SUPER_ADMIN")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/organizations",
                                "/api/organizations/**"
                        )
                        .hasAnyRole(
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        .requestMatchers("/api/users/**")
                        .hasAnyRole(
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        .requestMatchers("/api/admin/**")
                        .hasAnyRole(
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        .requestMatchers("/api/chats/**")
                        .authenticated()

                        .anyRequest()
                        .authenticated()
                )
                /*
                 * Resource Server получает только Authorization header.
                 *
                 * Spring Security автоматически исключает bearer-header
                 * requests из CSRF-проверки. Это безопасно, поскольку
                 * браузер не отправляет Authorization header автоматически.
                 */
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(
                                bearerTokenResolver
                        )
                        .authenticationEntryPoint(
                                authenticationEntryPoint
                        )
                        .accessDeniedHandler(
                                accessDeniedHandler
                        )
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        resourceServerJwtAuthenticationConverter()
                                )
                        )
                )
                .addFilterAfter(
                        new CsrfCookieFilter(),
                        BasicAuthenticationFilter.class
                )
                /*
                 * Cookie JWT обрабатывается отдельным фильтром после
                 * CsrfFilter. Поэтому unsafe cookie-authenticated
                 * requests обязаны предъявить X-XSRF-TOKEN.
                 */
                .addFilterAfter(
                        accessCookieAuthenticationFilter,
                        BearerTokenAuthenticationFilter.class
                )
                .addFilterAfter(
                        userStatusFilter,
                        AccessCookieAuthenticationFilter.class
                )
                .build();
    }

    @Bean
    AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) {
        return configuration.getAuthenticationManager();
    }

    @Bean
    AuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(
                        userDetailsService
                );

        provider.setPasswordEncoder(passwordEncoder);

        return provider;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(
                corsProperties.allowedOrigins()
        );

        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                "X-Request-Id",
                "X-XSRF-TOKEN"
        ));

        configuration.setExposedHeaders(List.of(
                "X-Request-Id",
                HttpHeaders.RETRY_AFTER,
                HttpHeaders.WWW_AUTHENTICATE
        ));

        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3_600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository =
                CookieCsrfTokenRepository.withHttpOnlyFalse();

        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");

        repository.setCookieCustomizer(cookie -> {
            cookie
                    .sameSite(
                            authCookieProperties.sameSite()
                    )
                    .secure(
                            authCookieProperties.secure()
                    )
                    .httpOnly(false)
                    .path("/");

            if (authCookieProperties.hasDomain()) {
                cookie.domain(
                        authCookieProperties.domain()
                );
            }
        });

        return repository;
    }

    /**
     * Только Authorization header.
     *
     * <p>Access-cookie намеренно не возвращается этим resolver,
     * иначе OAuth2ResourceServerConfigurer автоматически исключит
     * cookie-authenticated request из CSRF-проверки.</p>
     */
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver headerResolver =
                new DefaultBearerTokenResolver();

        return request -> {
            if (isPublicAuthEndpoint(request)) {
                return null;
            }

            String headerToken =
                    headerResolver.resolve(request);

            return headerToken == null
                    || headerToken.isBlank()
                    ? null
                    : headerToken;
        };
    }

    private AuthenticationManager
    accessCookieAuthenticationManager(
            JwtDecoder jwtDecoder
    ) {
        JwtAuthenticationProvider provider =
                new JwtAuthenticationProvider(jwtDecoder);

        provider.setJwtAuthenticationConverter(
                resourceServerJwtAuthenticationConverter()
        );

        return new ProviderManager(provider);
    }

    private Converter<Jwt, AbstractAuthenticationToken>
    resourceServerJwtAuthenticationConverter() {
        return jwt -> {
            try {
                return safeAiJwtAuthenticationConverter
                        .convert(jwt);
            } catch (BadJwtException exception) {
                throw new InvalidBearerTokenException(
                        INVALID_ACCESS_TOKEN_MESSAGE,
                        exception
                );
            }
        };
    }

    private void configureHeaders(
            HeadersConfigurer<HttpSecurity> headers
    ) {
        headers
                .contentSecurityPolicy(csp ->
                        csp.policyDirectives(
                                "default-src 'self'; "
                                        + "frame-ancestors 'none'; "
                                        + "object-src 'none'"
                        )
                )
                .frameOptions(
                        HeadersConfigurer
                                .FrameOptionsConfig::deny
                )
                .contentTypeOptions(
                        Customizer.withDefaults()
                );

        headers.httpStrictTransportSecurity(hsts -> {
            if (hstsEnabled) {
                hsts
                        .includeSubDomains(true)
                        .preload(true)
                        .maxAgeInSeconds(31_536_000);
            } else {
                hsts.disable();
            }
        });
    }

    private boolean isPublicAuthEndpoint(
            HttpServletRequest request
    ) {
        return PUBLIC_AUTH_ENDPOINTS.contains(
                pathWithinApplication(request)
        );
    }

    private String pathWithinApplication(
            HttpServletRequest request
    ) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (contextPath != null
                && !contextPath.isEmpty()
                && requestUri.startsWith(contextPath)) {
            String path = requestUri.substring(
                    contextPath.length()
            );

            return path.isEmpty() ? "/" : path;
        }

        return requestUri;
    }
}