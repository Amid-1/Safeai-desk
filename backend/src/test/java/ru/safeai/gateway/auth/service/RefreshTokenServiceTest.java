package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;
import ru.safeai.gateway.common.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.organization.entity.OrganizationEntity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private ClientIpResolver clientIpResolver;

    private RefreshTokenService service;

    private static final Instant NOW =
            Instant.parse("2026-06-12T12:00:00Z");

    @BeforeEach
    void setUp() {
        AuthCookieProperties authCookieProperties = new AuthCookieProperties(
                false,
                "Lax",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                null
        );

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new RefreshTokenService(
                refreshTokenRepository,
                clientIpResolver,
                authCookieProperties,
                clock
        );
    }

    @Test
    void create_shouldStoreHashedTokenAndReturnRawToken() {
        UserEntity user = enabledUser();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");

        when(clientIpResolver.resolve(request))
                .thenReturn("127.0.0.1");

        String rawToken = service.create(user, request);

        assertThat(rawToken).isNotBlank();

        ArgumentCaptor<RefreshTokenEntity> captor =
                ArgumentCaptor.forClass(RefreshTokenEntity.class);

        verify(refreshTokenRepository).save(captor.capture());

        RefreshTokenEntity saved = captor.getValue();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getTokenHash()).isNotBlank();
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(saved.getTokenHash()).hasSize(64);
        assertThat(saved.getTokenFamilyId()).isNotNull();
        assertThat(saved.getExpiresAt())
                .isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(saved.getCreatedByIp()).isEqualTo("127.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("JUnit");
    }

    @Test
    void rotate_shouldRevokeOldTokenAndCreateNewToken() {
        UserEntity user = enabledUser();

        UUID tokenFamilyId = UUID.randomUUID();

        RefreshTokenEntity oldToken = new RefreshTokenEntity();
        oldToken.setId(UUID.randomUUID());
        oldToken.setUser(user);
        oldToken.setTokenFamilyId(tokenFamilyId);
        oldToken.setExpiresAt(NOW.plusSeconds(3600));
        oldToken.setRevokedAt(null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");

        when(refreshTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(oldToken));

        when(clientIpResolver.resolve(request))
                .thenReturn("127.0.0.1");

        RefreshTokenService.RefreshTokenRotationResult result =
                service.rotate("old-raw-token", request);

        assertThat(result.accessTokenSubject().userId())
                .isEqualTo(user.getId());

        assertThat(result.accessTokenSubject().organizationId())
                .isEqualTo(user.getOrganization().getId());

        assertThat(result.accessTokenSubject().email())
                .isEqualTo(user.getEmail());

        assertThat(result.accessTokenSubject().tokenVersion())
                .isEqualTo(user.getTokenVersion());

        assertThat(result.accessTokenSubject().roles())
                .containsExactly("ADMIN");
        assertThat(result.rawRefreshToken()).isNotBlank();
        assertThat(result.rawRefreshToken()).isNotEqualTo("old-raw-token");

        assertThat(oldToken.getLastUsedAt()).isNotNull();
        assertThat(oldToken.getRevokedAt()).isNotNull();

        ArgumentCaptor<RefreshTokenEntity> captor =
                ArgumentCaptor.forClass(RefreshTokenEntity.class);

        verify(refreshTokenRepository).save(captor.capture());

        RefreshTokenEntity newToken = captor.getValue();

        assertThat(newToken.getId()).isNotNull();
        assertThat(newToken.getUser()).isSameAs(user);
        assertThat(newToken.getTokenFamilyId()).isEqualTo(tokenFamilyId);
        assertThat(newToken.getTokenHash()).isNotBlank();
        assertThat(newToken.getTokenHash()).hasSize(64);
        assertThat(newToken.getCreatedByIp()).isEqualTo("127.0.0.1");
        assertThat(newToken.getUserAgent()).isEqualTo("JUnit");

        assertThat(oldToken.getReplacedByTokenId()).isEqualTo(newToken.getId());
    }

    @Test
    void rotate_shouldThrowWhenTokenDoesNotExist() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(refreshTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("raw-token", request))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("Refresh token не найден");

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotate_shouldThrowWhenTokenIsExpired() {
        RefreshTokenEntity oldToken = new RefreshTokenEntity();
        oldToken.setId(UUID.randomUUID());
        oldToken.setUser(enabledUser());
        oldToken.setTokenFamilyId(UUID.randomUUID());
        oldToken.setExpiresAt(NOW.minusSeconds(1));
        oldToken.setRevokedAt(null);

        MockHttpServletRequest request = new MockHttpServletRequest();

        when(refreshTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(oldToken));

        assertThatThrownBy(() -> service.rotate("raw-token", request))
                .isInstanceOf(ExpiredRefreshTokenException.class)
                .hasMessageContaining("Refresh token истек");

        assertThat(oldToken.getLastUsedAt()).isEqualTo(NOW);
        assertThat(oldToken.getRevokedAt()).isEqualTo(NOW);

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotate_shouldDetectReuseWhenTokenIsRevoked() {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID tokenFamilyId = UUID.randomUUID();

        UserEntity user = mock(UserEntity.class, RETURNS_DEEP_STUBS);
        when(user.getId()).thenReturn(userId);
        when(user.isEnabled()).thenReturn(true);
        when(user.getOrganization().getId()).thenReturn(organizationId);
        when(user.getOrganization().isEnabled()).thenReturn(true);

        RefreshTokenEntity oldToken = new RefreshTokenEntity();
        oldToken.setId(UUID.randomUUID());
        oldToken.setUser(user);
        oldToken.setTokenFamilyId(tokenFamilyId);
        oldToken.setExpiresAt(Instant.now().plusSeconds(3600));
        oldToken.setRevokedAt(NOW);

        MockHttpServletRequest request = new MockHttpServletRequest();

        when(refreshTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(oldToken));

        assertThatThrownBy(() -> service.rotate("raw-token", request))
                .isInstanceOf(RefreshTokenReuseDetectedException.class)
                .hasMessageContaining("Обнаружено повторное использование refresh token");

        verify(refreshTokenRepository).revokeAllActiveByTokenFamilyId(
                eq(tokenFamilyId),
                any(Instant.class)
        );

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void revoke_shouldCallRepositoryUpdateWhenTokenProvided() {
        service.revoke("raw-token");

        verify(refreshTokenRepository).revokeByTokenHash(
                anyString(),
                any(Instant.class)
        );
    }

    @Test
    void revoke_shouldDoNothingWhenTokenIsBlank() {
        service.revoke(" ");

        verifyNoInteractions(refreshTokenRepository);
    }

    private UserEntity enabledUser() {
        OrganizationEntity organization = new OrganizationEntity();
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
        user.setTokenVersion(0L);
        user.getRoles().add(role);

        return user;
    }

    @Test
    void revokeAndReturnUser_shouldRevokeTokenAndReturnUser() {
        UserEntity user = enabledUser();

        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setId(UUID.randomUUID());
        token.setUser(user);
        token.setTokenFamilyId(UUID.randomUUID());
        token.setExpiresAt(NOW.plusSeconds(3600));
        token.setRevokedAt(null);

        when(refreshTokenRepository.findByTokenHashWithUser(anyString()))
                .thenReturn(Optional.of(token));

        Optional<UserEntity> result = service.revokeAndReturnUser("raw-token");

        assertThat(result).containsSame(user);
        assertThat(token.getRevokedAt()).isNotNull();
        assertThat(token.getLastUsedAt()).isNotNull();
    }

    @Test
    void revokeAndReturnUser_shouldReturnEmptyWhenTokenIsBlank() {
        Optional<UserEntity> result = service.revokeAndReturnUser(" ");

        assertThat(result).isEmpty();
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void revokeAndReturnUser_shouldReturnEmptyWhenTokenNotFound() {
        when(refreshTokenRepository.findByTokenHashWithUser(anyString()))
                .thenReturn(Optional.empty());

        Optional<UserEntity> result = service.revokeAndReturnUser("raw-token");

        assertThat(result).isEmpty();
    }

    @Test
    void revokeAndReturnUser_shouldUpdateLastUsedAtWhenTokenAlreadyRevoked() {
        UserEntity user = enabledUser();

        Instant revokedAt = NOW.minusSeconds(60);

        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setId(UUID.randomUUID());
        token.setUser(user);
        token.setTokenFamilyId(UUID.randomUUID());
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setRevokedAt(revokedAt);

        when(refreshTokenRepository.findByTokenHashWithUser(anyString()))
                .thenReturn(Optional.of(token));

        Optional<UserEntity> result = service.revokeAndReturnUser("raw-token");

        assertThat(result).containsSame(user);
        assertThat(token.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(token.getLastUsedAt()).isNotNull();
    }
}