package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserStatusFilterTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private UserRepository userRepository;
    private UserStatusFilter filter;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);

        JsonSecurityErrorWriter errorWriter = new JsonSecurityErrorWriter(
                new tools.jackson.databind.ObjectMapper()
        );

        filter = new UserStatusFilter(userRepository, errorWriter);

        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_shouldReturn401WhenUserIsDisabled() throws Exception {
        SafeAiUserPrincipal principal = principal();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );

        UserEntity user = userEntity(false, 0L);

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_REVOKED");
    }

    @Test
    void doFilterInternal_shouldReturn401WhenTokenVersionMismatch() throws Exception {
        SafeAiUserPrincipal principal = principal();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );

        UserEntity user = userEntity(true, 1L);

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_REVOKED");
    }

    @Test
    void doFilterInternal_shouldContinueWhenUserIsValid() throws Exception {
        SafeAiUserPrincipal principal = principal();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );

        UserEntity user = userEntity(true, 0L);

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private SafeAiUserPrincipal principal() {
        return new SafeAiUserPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private UserEntity userEntity(boolean enabled, long tokenVersion) {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Demo Company");
        organization.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setOrganization(organization);
        user.setEmail("admin@test.com");
        user.setPasswordHash("encoded-password");
        user.setFullName("Demo Admin");
        user.setEnabled(enabled);
        user.setTokenVersion(tokenVersion);
        user.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));

        return user;
    }
}