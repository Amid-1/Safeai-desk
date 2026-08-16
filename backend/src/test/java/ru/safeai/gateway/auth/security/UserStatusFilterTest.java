package ru.safeai.gateway.auth.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication
        .UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority
        .SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiErrorResponseWriter;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.service.UserSecurityStatus;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatusFilterTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final long TOKEN_VERSION =
            5L;

    private static final long ORGANIZATION_AUTH_VERSION =
            3L;

    @Mock
    private UserStatusCacheService
            userStatusCacheService;

    @Mock
    private ApiErrorResponseWriter
            errorWriter;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder
                .clearContext();
    }

    @Test
    void requestWithoutSafeAiPrincipalPassesThrough()
            throws Exception {

        UserStatusFilter filter =
                filter();

        MockHttpServletRequest request =
                request();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilterInternal(
                request,
                response,
                filterChain
        );

        verify(filterChain)
                .doFilter(
                        request,
                        response
                );

        verifyNoInteractions(
                userStatusCacheService,
                errorWriter
        );
    }

    @Test
    void validSecurityStatePassesThrough()
            throws Exception {

        authenticate(
                principal()
        );

        when(
                userStatusCacheService
                        .getStatus(
                                USER_ID
                        )
        ).thenReturn(
                Optional.of(
                        status(
                                true,
                                true,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION
                        )
                )
        );

        UserStatusFilter filter =
                filter();

        MockHttpServletRequest request =
                request();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilterInternal(
                request,
                response,
                filterChain
        );

        verify(filterChain)
                .doFilter(
                        request,
                        response
                );

        verifyNoInteractions(
                errorWriter
        );
    }

    @Test
    void disabledUserReturnsControlled401AndStopsChain()
            throws Exception {

        authenticate(
                principal()
        );

        when(
                userStatusCacheService
                        .getStatus(
                                USER_ID
                        )
        ).thenReturn(
                Optional.of(
                        status(
                                false,
                                true,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION
                        )
                )
        );

        assertRejectedByFilter();
    }

    @Test
    void disabledOrganizationReturnsControlled401AndStopsChain()
            throws Exception {

        authenticate(
                principal()
        );

        when(
                userStatusCacheService
                        .getStatus(
                                USER_ID
                        )
        ).thenReturn(
                Optional.of(
                        status(
                                true,
                                false,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION
                        )
                )
        );

        assertRejectedByFilter();
    }

    @Test
    void staleUserTokenVersionReturnsControlled401AndStopsChain()
            throws Exception {

        authenticate(
                principal()
        );

        when(
                userStatusCacheService
                        .getStatus(
                                USER_ID
                        )
        ).thenReturn(
                Optional.of(
                        status(
                                true,
                                true,
                                TOKEN_VERSION + 1L,
                                ORGANIZATION_AUTH_VERSION
                        )
                )
        );

        assertRejectedByFilter();
    }

    @Test
    void staleOrganizationAuthVersionReturnsControlled401AndStopsChain()
            throws Exception {

        authenticate(
                principal()
        );

        when(
                userStatusCacheService
                        .getStatus(
                                USER_ID
                        )
        ).thenReturn(
                Optional.of(
                        status(
                                true,
                                true,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION + 1L
                        )
                )
        );

        assertRejectedByFilter();
    }

    @Test
    void differentOrganizationReturnsControlled401AndStopsChain()
            throws Exception {

        authenticate(
                principal()
        );

        UserSecurityStatus status =
                new UserSecurityStatus(
                        UUID.randomUUID(),
                        true,
                        true,
                        TOKEN_VERSION,
                        ORGANIZATION_AUTH_VERSION
                );

        when(
                userStatusCacheService
                        .getStatus(
                                USER_ID
                        )
        ).thenReturn(
                Optional.of(status)
        );

        assertRejectedByFilter();
    }

    @Test
    void missingUserReturnsControlled401AndStopsChain()
            throws Exception {

        authenticate(
                principal()
        );

        when(
                userStatusCacheService
                        .getStatus(
                                USER_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertRejectedByFilter();
    }

    @Test
    void securityStateStorageFailureReturnsControlled503()
            throws Exception {

        authenticate(
                principal()
        );

        when(
                userStatusCacheService
                        .getStatus(
                                USER_ID
                        )
        ).thenThrow(
                new IllegalStateException(
                        "PostgreSQL unavailable"
                )
        );

        UserStatusFilter filter =
                filter();

        MockHttpServletRequest request =
                request();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilterInternal(
                request,
                response,
                filterChain
        );

        verify(errorWriter)
                .write(
                        request,
                        response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ApiErrorCode.AUTH_SERVICE_UNAVAILABLE,
                        "Сервис проверки авторизации "
                                + "временно недоступен"
                );

        verify(filterChain, never())
                .doFilter(
                        request,
                        response
                );

        assertSecurityContextCleared();
    }

    private void assertRejectedByFilter()
            throws Exception {

        UserStatusFilter filter =
                filter();

        MockHttpServletRequest request =
                request();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilterInternal(
                request,
                response,
                filterChain
        );

        verify(errorWriter)
                .write(
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        ApiErrorCode.TOKEN_REVOKED,
                        "Токен больше не действителен"
                );

        verify(filterChain, never())
                .doFilter(
                        request,
                        response
                );

        assertSecurityContextCleared();
    }

    private void assertSecurityContextCleared() {
        assertThat(
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
        ).isNull();
    }

    private UserStatusFilter filter() {
        return new UserStatusFilter(
                userStatusCacheService,
                errorWriter
        );
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest(
                "GET",
                "/api/users"
        );
    }

    private UserSecurityStatus status(
            boolean userEnabled,
            boolean organizationEnabled,
            long tokenVersion,
            long organizationAuthVersion
    ) {
        return new UserSecurityStatus(
                ORGANIZATION_ID,
                userEnabled,
                organizationEnabled,
                tokenVersion,
                organizationAuthVersion
        );
    }

    private SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        TOKEN_VERSION,
                        ORGANIZATION_AUTH_VERSION,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_USER"
                                )
                        )
                );
    }

    private void authenticate(
            SafeAiUserPrincipal principal
    ) {
        SecurityContext context =
                SecurityContextHolder
                        .createEmptyContext();

        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );

        SecurityContextHolder
                .setContext(
                        context
                );
    }
}