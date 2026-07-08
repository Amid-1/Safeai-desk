package ru.safeai.gateway.auth.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.safeai.gateway.common.security.JsonSecurityErrorWriter;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.service.UserSecurityStatus;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserStatusFilterTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private UserStatusCacheService userStatusCacheService;
    private UserStatusFilter filter;

    @BeforeEach
    void setUp() {
        userStatusCacheService = mock(UserStatusCacheService.class);

        JsonSecurityErrorWriter errorWriter = new JsonSecurityErrorWriter(
                new tools.jackson.databind.ObjectMapper()
        );

        filter = new UserStatusFilter(userStatusCacheService, errorWriter);

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_shouldContinueWhenAuthenticationIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(userStatusCacheService);
    }

    @Test
    void doFilter_shouldReturn401WhenUserIsDisabled() throws Exception {
        setPrincipal();

        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.of(new UserSecurityStatus(
                        ORGANIZATION_ID,
                        false,
                        true,
                        0L
                )));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_REVOKED");
    }

    @Test
    void doFilter_shouldReturn401WhenOrganizationIsDisabled() throws Exception {
        setPrincipal();

        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.of(new UserSecurityStatus(
                        ORGANIZATION_ID,
                        true,
                        false,
                        0L
                )));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_REVOKED");
    }

    @Test
    void doFilter_shouldReturn401WhenTokenVersionMismatch() throws Exception {
        setPrincipal();

        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.of(new UserSecurityStatus(
                        ORGANIZATION_ID,
                        true,
                        true,
                        1L
                )));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_REVOKED");
    }

    @Test
    void doFilter_shouldReturn401WhenOrganizationIdMismatch() throws Exception {
        setPrincipal();

        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.of(new UserSecurityStatus(
                        OTHER_ORGANIZATION_ID,
                        true,
                        true,
                        0L
                )));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_REVOKED");
    }

    @Test
    void doFilter_shouldReturn401WhenStatusIsMissing() throws Exception {
        setPrincipal();

        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_REVOKED");
    }

    @Test
    void doFilter_shouldContinueWhenUserIsValid() throws Exception {
        setPrincipal();

        when(userStatusCacheService.getStatus(USER_ID))
                .thenReturn(Optional.of(new UserSecurityStatus(
                        ORGANIZATION_ID,
                        true,
                        true,
                        0L
                )));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void setPrincipal() {
        SafeAiUserPrincipal principal = new SafeAiUserPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );
    }
}