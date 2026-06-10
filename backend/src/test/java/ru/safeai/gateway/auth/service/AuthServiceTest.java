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
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                authenticationManager,
                jwtService,
                auditEventService
        );
    }

    @Test
    void loginWhenCredentialsAreValidReturnsTokenAndUser() {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        LoginRequest request = new LoginRequest(
                "admin@test.com",
                "admin123"
        );

        SafeAiUserPrincipal principal = new SafeAiUserPrincipal(
                userId,
                organizationId,
                "admin@test.com",
                "encoded-password",
                true,
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

        verify(auditEventService).record(
                eq(userId),
                eq(AuditEventType.USER_LOGIN_SUCCESS),
                anyMap()
        );
    }

    @Test
    void loginWhenCredentialsAreInvalidThrowsBadCredentialsException() {
        LoginRequest request = new LoginRequest(
                "admin@test.com",
                "wrong-password"
        );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        verify(auditEventService).record(
                isNull(),
                eq(AuditEventType.USER_LOGIN_FAILED),
                anyMap()
        );
    }
}