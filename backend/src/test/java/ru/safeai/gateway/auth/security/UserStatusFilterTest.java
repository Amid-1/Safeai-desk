package ru.safeai.gateway.auth.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatusFilterTest {

    private static final UUID USER_ID = UUID.fromString(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    );

    private static final UUID ORGANIZATION_ID = UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    );

    private static final UUID OTHER_ORGANIZATION_ID = UUID.fromString(
            "cccccccc-cccc-cccc-cccc-cccccccccccc"
    );

    @Mock
    private UserStatusCacheService userStatusCacheService;

    @Mock
    private ApiErrorResponseWriter errorWriter;

    @Mock
    private FilterChain filterChain;

    private UserStatusFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new UserStatusFilter(
                userStatusCacheService,
                errorWriter
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedRequestContinues()
            throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(
                userStatusCacheService,
                errorWriter
        );
    }

    @Test
    void validSecurityStatusContinuesWithoutReplacingAuthentication()
            throws Exception {
        Authentication authentication = setAuthentication();

        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.of(
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                true,
                                0L
                        )
                ));

        MockHttpServletRequest request = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(errorWriter);
        assertThat(SecurityContextHolder.getContext()
                .getAuthentication())
                .isSameAs(authentication);
    }

    @Test
    void disabledUserIsRejected()
            throws Exception {
        assertRejected(new UserSecurityStatus(
                ORGANIZATION_ID,
                false,
                true,
                0L
        ));
    }

    @Test
    void disabledOrganizationIsRejected()
            throws Exception {
        assertRejected(new UserSecurityStatus(
                ORGANIZATION_ID,
                true,
                false,
                0L
        ));
    }

    @Test
    void staleTokenVersionIsRejected()
            throws Exception {
        assertRejected(new UserSecurityStatus(
                ORGANIZATION_ID,
                true,
                true,
                1L
        ));
    }

    @Test
    void organizationMismatchIsRejected()
            throws Exception {
        assertRejected(new UserSecurityStatus(
                OTHER_ORGANIZATION_ID,
                true,
                true,
                0L
        ));
    }

    @Test
    void missingSecurityStatusIsRejected()
            throws Exception {
        setAuthentication();
        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.empty());

        assertCurrentRequestRejected();
    }

    private void assertRejected(
            UserSecurityStatus status
    ) throws Exception {
        setAuthentication();
        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.of(status));
        assertCurrentRequestRejected();
    }

    private void assertCurrentRequestRejected()
            throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(errorWriter).write(
                same(request),
                same(response),
                eq(HttpStatus.UNAUTHORIZED),
                eq(ApiErrorCode.TOKEN_REVOKED),
                eq("Токен больше не действителен")
        );
        verifyNoInteractions(filterChain);
        assertThat(SecurityContextHolder.getContext()
                .getAuthentication())
                .isNull();
    }

    private Authentication setAuthentication() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        "admin@test.com",
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );

        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        return authentication;
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest(
                "GET",
                "/api/chats"
        );
    }
}
