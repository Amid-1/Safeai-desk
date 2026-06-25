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
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private ClientIpResolver clientIpResolver;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(
                refreshTokenRepository,
                clientIpResolver
        );
    }

    @Test
    void create_shouldStoreHashedTokenAndReturnRawToken() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());

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

        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getTokenHash()).isNotBlank();
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(saved.getTokenHash()).hasSize(64);
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        assertThat(saved.getCreatedByIp()).isEqualTo("127.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("JUnit");
    }

    @Test
    void validate_shouldReturnTokenWhenTokenIsActive() {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setRevokedAt(null);

        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(token));

        RefreshTokenEntity result = service.validate("raw-token");

        assertThat(result).isSameAs(token);
    }

    @Test
    void validate_shouldThrowWhenTokenDoesNotExist() {
        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate("raw-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refresh token не найден");
    }

    @Test
    void validate_shouldThrowWhenTokenIsExpired() {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setExpiresAt(Instant.now().minusSeconds(1));
        token.setRevokedAt(null);

        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.validate("raw-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired or revoked");
    }

    @Test
    void validate_shouldThrowWhenTokenIsRevoked() {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setRevokedAt(Instant.now());

        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.validate("raw-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired or revoked");
    }

    @Test
    void revoke_shouldSetRevokedAtWhenTokenExists() {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setExpiresAt(Instant.now().plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(token));

        service.revoke("raw-token");

        assertThat(token.getRevokedAt()).isNotNull();
    }

    @Test
    void revoke_shouldDoNothingWhenTokenIsBlank() {
        service.revoke(" ");

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void revoke_shouldDoNothingWhenTokenDoesNotExist() {
        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.empty());

        service.revoke("raw-token");

        verify(refreshTokenRepository).findByTokenHash(anyString());
    }
}