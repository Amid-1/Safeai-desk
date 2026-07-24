package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;
import ru.safeai.gateway.common.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-06-12T12:00:00Z");

    private static final Duration IDLE_MAX_AGE =
            Duration.ofDays(30);

    private static final Duration ABSOLUTE_MAX_AGE =
            Duration.ofDays(90);

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private AuthCookieProperties authCookieProperties;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(
                refreshTokenRepository,
                userRepository,
                clientIpResolver,
                authCookieProperties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createForLoginStoresOnlyTokenHashAndFamilyMetadata() {
        stubRefreshTokenAgesForLogin();

        UserEntity user = enabledUser(0L);
        MockHttpServletRequest request = request();

        when(clientIpResolver.resolve(request))
                .thenReturn("127.0.0.1");

        RefreshTokenService.CreatedRefreshToken result =
                service.createForLogin(
                        user,
                        request,
                        NOW
                );

        ArgumentCaptor<RefreshTokenEntity> captor =
                ArgumentCaptor.forClass(
                        RefreshTokenEntity.class
                );

        verify(refreshTokenRepository).save(
                captor.capture()
        );

        RefreshTokenEntity saved = captor.getValue();

        assertThat(result.rawToken()).isNotBlank();
        assertThat(saved.getTokenHash())
                .hasSize(64)
                .isNotEqualTo(result.rawToken());
        assertThat(saved.getId()).isEqualTo(result.id());
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getIssuedTokenVersion()).isZero();
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getExpiresAt())
                .isEqualTo(NOW.plus(IDLE_MAX_AGE));
        assertThat(saved.getFamilyCreatedAt())
                .isEqualTo(NOW);
        assertThat(saved.getFamilyExpiresAt())
                .isEqualTo(NOW.plus(ABSOLUTE_MAX_AGE));
        assertThat(saved.getCreatedByIp())
                .isEqualTo("127.0.0.1");
        assertThat(saved.getUserAgent())
                .isEqualTo("JUnit");
        assertThat(result.cookieMaxAge())
                .isEqualTo(IDLE_MAX_AGE);
    }

    @Test
    void rotateRevokesOldTokenAndCreatesReplacementInSameFamily() {
        stubIdleRefreshTokenAge();

        UserEntity user = enabledUser(0L);
        RefreshTokenEntity oldToken = activeToken(user);
        MockHttpServletRequest request = request();

        when(refreshTokenRepository
                .findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(oldToken));
        when(userRepository
                .findByIdWithRolesAndOrganization(user.getId()))
                .thenReturn(Optional.of(user));
        when(clientIpResolver.resolve(request))
                .thenReturn("127.0.0.1");

        RefreshTokenService.RefreshTokenRotationResult result =
                service.rotate("old-raw-token", request);

        assertThat(oldToken.getRevokedAt())
                .isEqualTo(NOW);
        assertThat(oldToken.getRevocationReason())
                .isEqualTo(
                        RefreshTokenRevocationReason.ROTATED
                );
        assertThat(oldToken.getLastUsedAt())
                .isEqualTo(NOW);

        ArgumentCaptor<RefreshTokenEntity> captor =
                ArgumentCaptor.forClass(
                        RefreshTokenEntity.class
                );
        verify(refreshTokenRepository).save(
                captor.capture()
        );

        RefreshTokenEntity replacement =
                captor.getValue();

        assertThat(replacement.getTokenFamilyId())
                .isEqualTo(oldToken.getTokenFamilyId());
        assertThat(replacement.getFamilyCreatedAt())
                .isEqualTo(oldToken.getFamilyCreatedAt());
        assertThat(replacement.getFamilyExpiresAt())
                .isEqualTo(oldToken.getFamilyExpiresAt());
        assertThat(oldToken.getReplacedByTokenId())
                .isEqualTo(replacement.getId());
        assertThat(result.rawRefreshToken())
                .isNotBlank()
                .isNotEqualTo("old-raw-token");
        assertThat(result.accessTokenSubject().roles())
                .containsExactly("ADMIN");
    }

    @Test
    void rotatedTokenReuseTerminatesWholeFamily() {
        UserEntity user = enabledUser(0L);
        RefreshTokenEntity oldToken = activeToken(user);
        oldToken.setRevokedAt(NOW.minusSeconds(5));
        oldToken.setRevocationReason(
                RefreshTokenRevocationReason.ROTATED
        );

        when(refreshTokenRepository
                .findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(oldToken));
        when(userRepository
                .findByIdWithRolesAndOrganization(user.getId()))
                .thenReturn(Optional.of(user));
        when(refreshTokenRepository.terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason.REUSE_DETECTED
        )).thenReturn(2);

        assertThatThrownBy(() ->
                service.rotate(
                        "old-raw-token",
                        request()
                )
        ).isInstanceOf(
                RefreshTokenReuseDetectedException.class
        );

        verify(refreshTokenRepository).terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason.REUSE_DETECTED
        );
        verify(refreshTokenRepository, never())
                .save(any());
    }

    @Test
    void expiredTokenTerminatesFamily() {
        UserEntity user = enabledUser(0L);
        RefreshTokenEntity oldToken = activeToken(user);
        oldToken.setExpiresAt(NOW.minusNanos(1));

        when(refreshTokenRepository
                .findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(oldToken));
        when(userRepository
                .findByIdWithRolesAndOrganization(user.getId()))
                .thenReturn(Optional.of(user));
        when(refreshTokenRepository.terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason.EXPIRED
        )).thenReturn(1);

        assertThatThrownBy(() ->
                service.rotate("raw-token", request())
        ).isInstanceOf(
                ExpiredRefreshTokenException.class
        );

        verify(refreshTokenRepository).terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason.EXPIRED
        );
    }

    @Test
    void tokenVersionMismatchTerminatesFamily() {
        UserEntity user = enabledUser(1L);
        RefreshTokenEntity oldToken = activeToken(user);
        oldToken.setIssuedTokenVersion(0L);

        when(refreshTokenRepository
                .findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(oldToken));
        when(userRepository
                .findByIdWithRolesAndOrganization(user.getId()))
                .thenReturn(Optional.of(user));
        when(refreshTokenRepository.terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason
                        .SECURITY_STATE_CHANGED
        )).thenReturn(1);

        assertThatThrownBy(() ->
                service.rotate("raw-token", request())
        ).isInstanceOf(
                InvalidRefreshTokenException.class
        );

        verify(refreshTokenRepository).terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason
                        .SECURITY_STATE_CHANGED
        );
    }

    @Test
    void missingTokenReturnsInvalidRefreshToken() {
        when(refreshTokenRepository
                .findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.rotate("raw-token", request())
        )
                .isInstanceOf(
                        InvalidRefreshTokenException.class
                )
                .hasMessage(
                        "Недействительный refresh token"
                )
                .hasRootCauseMessage(
                        "Refresh token не найден"
                );

        verify(refreshTokenRepository, never())
                .save(any());
    }

    @Test
    void logoutTerminatesFamilyAndReturnsAuditSubject() {
        UserEntity user = enabledUser(0L);
        RefreshTokenEntity token = activeToken(user);

        when(refreshTokenRepository
                .findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(token));
        when(userRepository.findByIdWithOrganization(
                user.getId()
        )).thenReturn(Optional.of(user));
        when(refreshTokenRepository.terminateFamily(
                token.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason.LOGOUT
        )).thenReturn(1);

        Optional<LogoutAuditSubject> result =
                service.revokeFamilyAndReturnSubject(
                        "raw-token"
                );

        assertThat(result).contains(
                new LogoutAuditSubject(
                        user.getId(),
                        user.getOrganization().getId(),
                        "admin@test.com"
                )
        );

        verify(refreshTokenRepository).terminateFamily(
                token.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason.LOGOUT
        );
    }

    @Test
    void logoutOfTerminalFamilyIsIdempotent() {
        UserEntity user = enabledUser(0L);
        RefreshTokenEntity token = activeToken(user);
        token.setRevokedAt(NOW.minusSeconds(60));
        token.setRevocationReason(
                RefreshTokenRevocationReason.LOGOUT
        );

        when(refreshTokenRepository
                .findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(token));

        assertThat(service.revokeFamilyAndReturnSubject(
                "raw-token"
        )).isEmpty();

        verifyNoInteractions(userRepository);
        verify(refreshTokenRepository, never())
                .terminateFamily(
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    void logoutWithMissingUserClosesFamilyWithoutAuditSubject() {
        UserEntity user = enabledUser(0L);
        RefreshTokenEntity token = activeToken(user);

        when(refreshTokenRepository
                .findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(token));
        when(userRepository.findByIdWithOrganization(
                user.getId()
        )).thenReturn(Optional.empty());
        when(refreshTokenRepository.terminateFamily(
                token.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason
                        .SECURITY_STATE_CHANGED
        )).thenReturn(1);

        assertThat(service.revokeFamilyAndReturnSubject(
                "raw-token"
        )).isEmpty();

        verify(refreshTokenRepository).terminateFamily(
                token.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason
                        .SECURITY_STATE_CHANGED
        );
    }

    @Test
    void malformedRawTokenIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() ->
                service.revokeFamilyAndReturnSubject(" ")
        ).isInstanceOf(
                InvalidRefreshTokenException.class
        );

        verifyNoInteractions(
                refreshTokenRepository,
                userRepository
        );
    }

    private void stubRefreshTokenAgesForLogin() {
        stubIdleRefreshTokenAge();

        when(authCookieProperties.refreshTokenAbsoluteMaxAge())
                .thenReturn(ABSOLUTE_MAX_AGE);
    }

    private void stubIdleRefreshTokenAge() {
        when(authCookieProperties.refreshTokenMaxAge())
                .thenReturn(IDLE_MAX_AGE);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");
        return request;
    }

    private RefreshTokenEntity activeToken(
            UserEntity user
    ) {
        RefreshTokenEntity token =
                new RefreshTokenEntity();

        token.setId(UUID.randomUUID());
        token.setUser(user);
        token.setTokenFamilyId(UUID.randomUUID());
        token.setCreatedAt(NOW.minus(Duration.ofDays(1)));
        token.setExpiresAt(NOW.plus(Duration.ofDays(1)));
        token.setFamilyCreatedAt(
                NOW.minus(Duration.ofDays(1))
        );
        token.setFamilyExpiresAt(
                NOW.plus(Duration.ofDays(60))
        );
        token.setIssuedTokenVersion(
                user.getTokenVersion()
        );

        return token;
    }

    private UserEntity enabledUser(
            long tokenVersion
    ) {
        OrganizationEntity organization =
                new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Demo Company");
        organization.setEnabled(true);

        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName("ADMIN");

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setOrganization(organization);
        user.setEmail("admin@test.com");
        user.setPasswordHash("hash");
        user.setEnabled(true);
        user.setTokenVersion(tokenVersion);
        user.setRoles(new HashSet<>(Set.of(role)));

        return user;
    }
}