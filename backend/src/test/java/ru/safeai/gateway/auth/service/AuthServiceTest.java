package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.dto.LoginResponse;
import ru.safeai.gateway.auth.mapper.AuthUserMapper;
import ru.safeai.gateway.common.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.repository.UserRepository;

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
    private AuditEventService auditEventService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LoginRateLimitService loginRateLimitService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        AuthUserMapper authUserMapper = new AuthUserMapper();

        authService = new AuthService(
                authenticationManager,
                jwtService,
                auditEventService,
                authUserMapper,
                userRepository,
                loginRateLimitService
        );
    }

    @Test
    void loginWhenCredentialsAreValidReturnsTokenAndUser() {
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

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);

        when(jwtService.generateToken(principal))
                .thenReturn("test-jwt-token");

        LoginResponse response = authService.login(request);

        assertThat(response.token()).isEqualTo("test-jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");

        assertThat(response.user().id()).isEqualTo(userId);
        assertThat(response.user().organizationId()).isEqualTo(organizationId);
        assertThat(response.user().email()).isEqualTo("admin@test.com");
        assertThat(response.user().enabled()).isTrue();
        assertThat(response.user().roles()).containsExactly("ADMIN");

        verify(loginRateLimitService).checkAllowed("admin@test.com");

        verify(authenticationManager).authenticate(argThat(auth ->
                auth instanceof UsernamePasswordAuthenticationToken
                        && "admin@test.com".equals(auth.getPrincipal())
                        && "admin123".equals(auth.getCredentials())
        ));

        verify(auditEventService).record(
                eq(userId),
                eq(AuditEventType.USER_LOGIN_SUCCESS),
                anyMap()
        );
    }

    @Test
    void loginWhenCredentialsAreInvalidThrowsBadCredentialsException() {
        LoginRequest request = new LoginRequest(
                " Admin@Test.COM ",
                "wrong-password"
        );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginRateLimitService).checkAllowed("admin@test.com");

        verify(auditEventService).record(
                isNull(),
                eq(AuditEventType.USER_LOGIN_FAILED),
                anyMap()
        );
    }
}