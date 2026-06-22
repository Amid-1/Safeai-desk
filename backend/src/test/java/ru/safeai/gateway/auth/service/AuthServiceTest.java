package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthEventService authEventService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LoginRateLimitService loginRateLimitService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private AuthCookieService authCookieService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        ClientIpResolver clientIpResolver = new ClientIpResolver();

        authService = new AuthService(
                authenticationManager,
                jwtService,
                authEventService,
                userRepository,
                loginRateLimitService,
                clientIpResolver,
                refreshTokenService,
                authCookieService
        );
    }

    @Test
    void loginWhenCredentialsAreValidReturnsCurrentUserAndSetsCookies() {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        LoginRequest request = new LoginRequest(
                " Admin@Test.COM ",
                "admin123"
        );

        SafeAiUserPrincipal principal = new SafeAiUserPrincipal(
                userId,
                organizationId,
                "admin@test.com",
                "encoded-password",
                true,
                0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        UserEntity user = userEntity(userId, organizationId);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);

        when(userRepository.findByIdWithRolesAndOrganization(userId))
                .thenReturn(Optional.of(user));

        when(jwtService.generateAccessToken(user))
                .thenReturn("access-token");

        when(jwtService.generateAccessToken(user))
                .thenReturn("access-token");

        when(refreshTokenService.create(
                eq(user),
                any(MockHttpServletRequest.class)
        )).thenReturn("refresh-token");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse httpResponse = new MockHttpServletResponse();

        CurrentUserResponse response = authService.login(request, httpRequest, httpResponse);

        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.organizationId()).isEqualTo(organizationId);
        assertThat(response.email()).isEqualTo("admin@test.com");
        assertThat(response.fullName()).isEqualTo("Demo Admin");
        assertThat(response.enabled()).isTrue();
        assertThat(response.roles()).containsExactly("ADMIN");

        verify(loginRateLimitService).checkAllowed("admin@test.com", "127.0.0.1");

        verify(authenticationManager).authenticate(argThat(auth ->
                auth instanceof UsernamePasswordAuthenticationToken
                        && "admin@test.com".equals(auth.getPrincipal())
                        && "admin123".equals(auth.getCredentials())
        ));

        verify(authCookieService).addAccessTokenCookie(httpResponse, "access-token");
        verify(authCookieService).addRefreshTokenCookie(httpResponse, "refresh-token");

        verify(authEventService).loginSuccess(
                eq(principal),
                any(MockHttpServletRequest.class)
        );

        verify(loginRateLimitService).resetEmailLimit("admin@test.com");
    }

    @Test
    void loginWhenCredentialsAreInvalidThrowsBadCredentialsException() {
        LoginRequest request = new LoginRequest(
                " Admin@Test.COM ",
                "wrong-password"
        );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse httpResponse = new MockHttpServletResponse();

        assertThatThrownBy(() -> authService.login(request, httpRequest, httpResponse))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginRateLimitService).checkAllowed("admin@test.com", "127.0.0.1");

        verify(authEventService).loginFailed(
                eq("admin@test.com"),
                any(MockHttpServletRequest.class)
        );
    }

    private UserEntity userEntity(UUID userId, UUID organizationId) {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(organizationId);
        organization.setName("Test Org");

        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName("ADMIN");

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganization(organization);
        user.setEmail("admin@test.com");
        user.setPasswordHash("encoded-password");
        user.setFullName("Demo Admin");
        user.setEnabled(true);
        user.setTokenVersion(0L);
        user.setRoles(Set.of(role));

        return user;
    }
}