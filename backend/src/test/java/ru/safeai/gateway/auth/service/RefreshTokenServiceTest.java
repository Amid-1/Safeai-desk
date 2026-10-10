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
import ru.safeai.gateway.auth.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.auth.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.auth.exception.RefreshTokenReuseDetectedException;
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
            Instant.parse(
                    "2026-06-12T12:00:00Z"
            );

    private static final Duration IDLE_MAX_AGE =
            Duration.ofDays(
                    30
            );

    private static final Duration ABSOLUTE_MAX_AGE =
            Duration.ofDays(
                    90
            );

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private AuthCookieProperties authCookieProperties;

    @Mock
    private RefreshReuseAuditService refreshReuseAuditService;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service =
                new RefreshTokenService(
                        refreshTokenRepository,
                        userRepository,
                        clientIpResolver,
                        authCookieProperties,
                        refreshReuseAuditService,
                        Clock.fixed(
                                NOW,
                                ZoneOffset.UTC
                        )
                );
    }

    @Test
    void createForLoginStoresOnlyTokenHashAndFamilyMetadata() {
        stubRefreshTokenAgesForLogin();

        UserEntity user =
                enabledUser(
                        0L
                );

        MockHttpServletRequest request =
                request();

        when(
                clientIpResolver.resolve(
                        request
                )
        ).thenReturn(
                "127.0.0.1"
        );

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

        verify(
                refreshTokenRepository
        ).save(
                captor.capture()
        );

        RefreshTokenEntity saved =
                captor.getValue();

        assertThat(
                result.rawToken()
        ).isNotBlank();

        assertThat(
                saved.getTokenHash()
        )
                .hasSize(
                        64
                )
                .isNotEqualTo(
                        result.rawToken()
                );

        assertThat(
                saved.getId()
        ).isEqualTo(
                result.id()
        );

        assertThat(
                saved.getUser()
        ).isSameAs(
                user
        );

        assertThat(
                saved.getIssuedTokenVersion()
        ).isZero();

        assertThat(saved.getIssuedOrganizationAuthVersion())
                .isEqualTo(user.getOrganization().getAuthVersion());

        assertThat(
                saved.getCreatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                saved.getExpiresAt()
        ).isEqualTo(
                NOW.plus(
                        IDLE_MAX_AGE
                )
        );

        assertThat(
                saved.getFamilyCreatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                saved.getFamilyExpiresAt()
        ).isEqualTo(
                NOW.plus(
                        ABSOLUTE_MAX_AGE
                )
        );

        assertThat(
                saved.getCreatedByIp()
        ).isEqualTo(
                "127.0.0.1"
        );

        assertThat(
                saved.getUserAgent()
        ).isEqualTo(
                "JUnit"
        );

        assertThat(
                result.cookieMaxAge()
        ).isEqualTo(
                IDLE_MAX_AGE
        );
    }

    @Test
    void rotateRevokesOldTokenAndCreatesReplacementInSameFamily() {
        stubIdleRefreshTokenAge();

        UserEntity user =
                enabledUser(
                        0L
                );

        RefreshTokenEntity oldToken =
                activeToken(
                        user
                );

        MockHttpServletRequest request =
                request();

        stubLockedToken(oldToken.getUser(), oldToken);


        when(
                clientIpResolver.resolve(
                        request
                )
        ).thenReturn(
                "127.0.0.1"
        );

        RefreshTokenService.RefreshTokenRotationResult result =
                service.rotate(
                        "old-raw-token",
                        request
                );

        assertThat(
                oldToken.getRevokedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                oldToken.getRevocationReason()
        ).isEqualTo(
                RefreshTokenRevocationReason.ROTATED
        );

        assertThat(
                oldToken.getLastUsedAt()
        ).isEqualTo(
                NOW
        );

        ArgumentCaptor<RefreshTokenEntity> captor =
                ArgumentCaptor.forClass(
                        RefreshTokenEntity.class
                );

        verify(
                refreshTokenRepository
        ).save(
                captor.capture()
        );

        RefreshTokenEntity replacement =
                captor.getValue();

        assertThat(
                replacement.getTokenFamilyId()
        ).isEqualTo(
                oldToken.getTokenFamilyId()
        );

        assertThat(replacement.getIssuedTokenVersion())
                .isEqualTo(user.getTokenVersion());
        assertThat(replacement.getIssuedOrganizationAuthVersion())
                .isEqualTo(user.getOrganization().getAuthVersion());

        assertThat(
                replacement.getFamilyCreatedAt()
        ).isEqualTo(
                oldToken.getFamilyCreatedAt()
        );

        assertThat(
                replacement.getFamilyExpiresAt()
        ).isEqualTo(
                oldToken.getFamilyExpiresAt()
        );

        assertThat(
                oldToken.getReplacedByTokenId()
        ).isEqualTo(
                replacement.getId()
        );

        assertThat(
                result.rawRefreshToken()
        )
                .isNotBlank()
                .isNotEqualTo(
                        "old-raw-token"
                );

        assertThat(
                result.accessTokenSubject()
                        .roles()
        ).containsExactly(
                "ADMIN"
        );

        assertSecurityLockOrder(user);
        ArgumentCaptor<String> discoveryHash = ArgumentCaptor.forClass(String.class);
        verify(refreshTokenRepository).findLockIdentityByTokenHash(discoveryHash.capture());
        assertThat(discoveryHash.getValue())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo("old-raw-token");
        verify(refreshTokenRepository).findByTokenHashForUpdate(discoveryHash.getValue());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void rotatedTokenReuseTerminatesWholeFamilyAndWritesRequiredAudit() {
        UserEntity user =
                enabledUser(
                        0L
                );

        RefreshTokenEntity oldToken =
                activeToken(
                        user
                );

        oldToken.setRevokedAt(
                NOW.minusSeconds(
                        5
                )
        );

        oldToken.setRevocationReason(
                RefreshTokenRevocationReason.ROTATED
        );

        MockHttpServletRequest request =
                request();

        stubLockedToken(oldToken.getUser(), oldToken);


        when(
                refreshTokenRepository
                        .terminateFamily(
                                oldToken.getTokenFamilyId(),
                                NOW,
                                RefreshTokenRevocationReason
                                        .REUSE_DETECTED
                        )
        ).thenReturn(
                2
        );

        assertThatThrownBy(() ->
                service.rotate(
                        "old-raw-token",
                        request
                )
        ).isInstanceOf(
                RefreshTokenReuseDetectedException.class
        );

        verify(
                refreshTokenRepository
        ).terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason
                        .REUSE_DETECTED
        );

        verify(
                refreshReuseAuditService
        ).recordRequired(
                user.getId(),
                user.getOrganization()
                        .getId(),
                oldToken.getTokenFamilyId(),
                request
        );

        verify(
                refreshTokenRepository,
                never()
        ).save(
                any()
        );

        org.mockito.InOrder sequence = org.mockito.Mockito.inOrder(
                refreshTokenRepository, userRepository, refreshReuseAuditService);
        sequence.verify(refreshTokenRepository).findLockIdentityByTokenHash(anyString());
        sequence.verify(userRepository).lockOrganizationForAuthentication(
                user.getOrganization().getId());
        sequence.verify(userRepository).findByIdForSecurityUpdate(user.getId());
        sequence.verify(refreshTokenRepository).findByTokenHashForUpdate(anyString());
        sequence.verify(refreshTokenRepository).terminateFamily(
                oldToken.getTokenFamilyId(), NOW,
                RefreshTokenRevocationReason.REUSE_DETECTED);
        sequence.verify(refreshReuseAuditService).recordRequired(
                user.getId(), user.getOrganization().getId(),
                oldToken.getTokenFamilyId(), request);
    }

    @Test
    void expiredTokenTerminatesFamilyWithoutReuseAudit() {
        UserEntity user =
                enabledUser(
                        0L
                );

        RefreshTokenEntity oldToken =
                activeToken(
                        user
                );

        oldToken.setExpiresAt(
                NOW.minusNanos(
                        1
                )
        );

        stubLockedToken(oldToken.getUser(), oldToken);


        when(
                refreshTokenRepository
                        .terminateFamily(
                                oldToken.getTokenFamilyId(),
                                NOW,
                                RefreshTokenRevocationReason.EXPIRED
                        )
        ).thenReturn(
                1
        );

        assertThatThrownBy(() ->
                service.rotate(
                        "raw-token",
                        request()
                )
        ).isInstanceOf(
                ExpiredRefreshTokenException.class
        );

        verify(
                refreshTokenRepository
        ).terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason.EXPIRED
        );

        verifyNoInteractions(
                refreshReuseAuditService
        );
    }

    @Test
    void tokenVersionMismatchTerminatesFamily() {
        UserEntity user =
                enabledUser(
                        1L
                );

        RefreshTokenEntity oldToken =
                activeToken(
                        user
                );

        oldToken.setIssuedTokenVersion(
                0L
        );

        stubLockedToken(oldToken.getUser(), oldToken);


        when(
                refreshTokenRepository
                        .terminateFamily(
                                oldToken.getTokenFamilyId(),
                                NOW,
                                RefreshTokenRevocationReason
                                        .SECURITY_STATE_CHANGED
                        )
        ).thenReturn(
                1
        );

        assertThatThrownBy(() ->
                service.rotate(
                        "raw-token",
                        request()
                )
        ).isInstanceOf(
                InvalidRefreshTokenException.class
        );

        verify(
                refreshTokenRepository
        ).terminateFamily(
                oldToken.getTokenFamilyId(),
                NOW,
                RefreshTokenRevocationReason
                        .SECURITY_STATE_CHANGED
        );
    }

    @Test
    void missingTokenReturnsInvalidRefreshToken() {
        // The first lookup is intentionally non-locking and returns no identity.
        when(refreshTokenRepository.findLockIdentityByTokenHash(anyString()))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).findLockIdentityByTokenHash(anyString());
        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(anyString());
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).terminateFamily(any(), any(), any());
        verifyNoInteractions(userRepository, refreshReuseAuditService);
    }

    @Test
    void logoutTerminatesFamilyAndReturnsAuditSubject() {
        UserEntity user =
                enabledUser(
                        0L
                );

        RefreshTokenEntity token =
                activeToken(
                        user
                );

        stubLockedToken(token.getUser(), token);


        when(
                refreshTokenRepository
                        .terminateFamily(
                                token.getTokenFamilyId(),
                                NOW,
                                RefreshTokenRevocationReason.LOGOUT
                        )
        ).thenReturn(
                1
        );

        Optional<LogoutAuditSubject> result =
                service.revokeFamilyAndReturnSubject(
                        "raw-token"
                );

        assertThat(
                result
        ).contains(
                new LogoutAuditSubject(
                        user.getId(),
                        user.getOrganization()
                                .getId(),
                        "admin@test.com"
                )
        );

        verify(
                refreshTokenRepository
        ).terminateFamily(
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
        token.setRevocationReason(RefreshTokenRevocationReason.LOGOUT);
        stubLockedToken(user, token);

        assertThat(service.revokeFamilyAndReturnSubject("raw-token")).isEmpty();

        assertSecurityLockOrder(user);
        verify(refreshTokenRepository, never()).terminateFamily(any(), any(), any());
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void logoutWithMissingUserFailsClosedBeforeLockingTokenOrWritingAudit() {
        UserEntity user = enabledUser(0L);

        stubTokenDiscovery(user);
        stubOrganizationSecurityLock(user);
        when(userRepository.findByIdForSecurityUpdate(user.getId()))
                .thenReturn(Optional.empty());

        // A deleted or changed subject must not be trusted after the scalar
        // discovery. No token lock or family write is permitted without it.
        assertThatThrownBy(() -> service.revokeFamilyAndReturnSubject("raw-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).findLockIdentityByTokenHash(anyString());
        verify(userRepository).lockOrganizationForAuthentication(user.getOrganization().getId());
        verify(userRepository).findByIdForSecurityUpdate(user.getId());
        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(anyString());
        verify(refreshTokenRepository, never()).terminateFamily(any(), any(), any());
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void malformedRawTokenIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() ->
                service.revokeFamilyAndReturnSubject(
                        " "
                )
        ).isInstanceOf(
                InvalidRefreshTokenException.class
        );

        verifyNoInteractions(
                refreshTokenRepository,
                userRepository,
                refreshReuseAuditService
        );
    }

    // V72: security-state and row-lock ordering regression coverage.
    @Test
    void organizationAuthEpochMismatchTerminatesFamilyWithoutRotation() {
        UserEntity user = enabledUser(0L);
        RefreshTokenEntity token = activeToken(user);
        token.setIssuedOrganizationAuthVersion(
                user.getOrganization().getAuthVersion() + 1L);
        stubLockedToken(user, token);
        stubFamilyTermination(token, RefreshTokenRevocationReason.SECURITY_STATE_CHANGED);

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertSecurityLockOrder(user);
        verify(refreshTokenRepository).terminateFamily(
                token.getTokenFamilyId(), NOW,
                RefreshTokenRevocationReason.SECURITY_STATE_CHANGED);
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void disabledOrganizationTerminatesFamilyWithoutRotation() {
        UserEntity user = enabledUser(0L);
        user.getOrganization().setEnabled(false);
        RefreshTokenEntity token = activeToken(user);
        stubLockedToken(user, token);
        stubFamilyTermination(token, RefreshTokenRevocationReason.ORGANIZATION_DISABLED);

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).terminateFamily(
                token.getTokenFamilyId(), NOW,
                RefreshTokenRevocationReason.ORGANIZATION_DISABLED);
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void disabledUserTerminatesFamilyWithoutRotation() {
        UserEntity user = enabledUser(0L);
        user.setEnabled(false);
        RefreshTokenEntity token = activeToken(user);
        stubLockedToken(user, token);
        stubFamilyTermination(token, RefreshTokenRevocationReason.USER_DISABLED);

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).terminateFamily(
                token.getTokenFamilyId(), NOW,
                RefreshTokenRevocationReason.USER_DISABLED);
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void absentOrganizationDuringSecurityLockFailsClosedBeforeUserRead() {
        UserEntity user = enabledUser(0L);
        stubTokenDiscovery(user);
        when(userRepository.lockOrganizationForAuthentication(
                user.getOrganization().getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(userRepository, never()).findByIdForSecurityUpdate(any());
        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(anyString());
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).terminateFamily(any(), any(), any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void organizationScopeChangedBetweenDiscoveryAndUserLockFailsClosed() {
        UserEntity user = enabledUser(0L);
        UUID oldOrganizationId = UUID.randomUUID();
        when(refreshTokenRepository.findLockIdentityByTokenHash(anyString()))
                .thenReturn(java.util.Collections.singletonList(
                        new Object[]{user.getId(), oldOrganizationId}));
        when(userRepository.lockOrganizationForAuthentication(oldOrganizationId))
                .thenReturn(Optional.of(oldOrganizationId));
        when(userRepository.findByIdForSecurityUpdate(user.getId()))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(anyString());
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).terminateFamily(any(), any(), any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void tokenOwnershipChangedBetweenDiscoveryAndRowLockFailsClosed() {
        UserEntity user = enabledUser(0L);
        UserEntity differentUser = enabledUser(0L);
        RefreshTokenEntity token = activeToken(differentUser);
        stubLockedToken(user, token);

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertSecurityLockOrder(user);
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).terminateFamily(any(), any(), any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void missingTokenAfterSecurityLocksFailsClosedWithoutSideEffects() {
        UserEntity user = enabledUser(0L);
        stubTokenDiscovery(user);
        stubOrganizationSecurityLock(user);
        when(userRepository.findByIdForSecurityUpdate(user.getId()))
                .thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertSecurityLockOrder(user);
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).terminateFamily(any(), any(), any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void logoutOnRotatedPredecessorClosesFamilyAndReturnsAuditIdentity() {
        UserEntity user = enabledUser(0L);
        RefreshTokenEntity token = activeToken(user);
        token.setRevokedAt(NOW.minusSeconds(20));
        token.setRevocationReason(RefreshTokenRevocationReason.ROTATED);
        stubLockedToken(user, token);
        stubFamilyTermination(token, RefreshTokenRevocationReason.LOGOUT);

        assertThat(service.revokeFamilyAndReturnSubject("raw-token"))
                .contains(new LogoutAuditSubject(
                        user.getId(), user.getOrganization().getId(), "admin@test.com"));

        assertSecurityLockOrder(user);
        verify(refreshTokenRepository).terminateFamily(
                token.getTokenFamilyId(), NOW, RefreshTokenRevocationReason.LOGOUT);
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(refreshReuseAuditService);
    }

    @Test
    void duplicateDiscoveryProjectionFailsClosedWithoutAnySecurityMutation() {
        UserEntity user = enabledUser(0L);
        Object[] identity = {user.getId(), user.getOrganization().getId()};
        when(refreshTokenRepository.findLockIdentityByTokenHash(anyString()))
                .thenReturn(java.util.List.of(identity, identity));

        assertThatThrownBy(() -> service.rotate("raw-token", request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity projection");

        verifyNoInteractions(userRepository, refreshReuseAuditService);
        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(anyString());
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).terminateFamily(any(), any(), any());
    }

    /**
     * Sets the exact V72 sequence: scalar, unlocked token identity discovery,
     * org FOR SHARE, user FOR UPDATE, then token FOR UPDATE.
     * Mockito mocks have no real PostgreSQL locks; integration tests must
     * still verify their serializability under concurrent disable.
     */
    private void stubLockedToken(UserEntity user, RefreshTokenEntity token) {
        stubTokenDiscovery(user);
        stubOrganizationSecurityLock(user);
        when(userRepository.findByIdForSecurityUpdate(user.getId()))
                .thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.of(token));
    }

    private void stubTokenDiscovery(UserEntity user) {
        when(refreshTokenRepository.findLockIdentityByTokenHash(anyString()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{
                        user.getId(), user.getOrganization().getId()
                }));
    }

    private void stubOrganizationSecurityLock(UserEntity user) {
        UUID organizationId = user.getOrganization().getId();
        when(userRepository.lockOrganizationForAuthentication(organizationId))
                .thenReturn(Optional.of(organizationId));
    }

    private void stubFamilyTermination(RefreshTokenEntity token,
                                       RefreshTokenRevocationReason reason) {
        when(refreshTokenRepository.terminateFamily(
                token.getTokenFamilyId(), NOW, reason)).thenReturn(1);
    }

    private void assertSecurityLockOrder(UserEntity user) {
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                refreshTokenRepository, userRepository);
        order.verify(refreshTokenRepository).findLockIdentityByTokenHash(anyString());
        order.verify(userRepository).lockOrganizationForAuthentication(
                user.getOrganization().getId());
        order.verify(userRepository).findByIdForSecurityUpdate(user.getId());
        order.verify(refreshTokenRepository).findByTokenHashForUpdate(anyString());
    }

    private void stubRefreshTokenAgesForLogin() {
        stubIdleRefreshTokenAge();

        when(
                authCookieProperties
                        .refreshTokenAbsoluteMaxAge()
        ).thenReturn(
                ABSOLUTE_MAX_AGE
        );
    }

    private void stubIdleRefreshTokenAge() {
        when(
                authCookieProperties
                        .refreshTokenMaxAge()
        ).thenReturn(
                IDLE_MAX_AGE
        );
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                "User-Agent",
                "JUnit"
        );

        return request;
    }

    private RefreshTokenEntity activeToken(
            UserEntity user
    ) {
        RefreshTokenEntity token =
                new RefreshTokenEntity();

        token.setId(
                UUID.randomUUID()
        );

        token.setUser(
                user
        );

        token.setTokenFamilyId(
                UUID.randomUUID()
        );

        token.setCreatedAt(
                NOW.minus(
                        Duration.ofDays(
                                1
                        )
                )
        );

        token.setExpiresAt(
                NOW.plus(
                        Duration.ofDays(
                                1
                        )
                )
        );

        token.setFamilyCreatedAt(
                NOW.minus(
                        Duration.ofDays(
                                1
                        )
                )
        );

        token.setFamilyExpiresAt(
                NOW.plus(
                        Duration.ofDays(
                                60
                        )
                )
        );

        token.setIssuedTokenVersion(
                user.getTokenVersion()
        );

        token.setIssuedOrganizationAuthVersion(
                user.getOrganization()
                        .getAuthVersion()
        );

        return token;
    }

    private UserEntity enabledUser(
            long tokenVersion
    ) {
        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setId(
                UUID.randomUUID()
        );

        organization.setName(
                "Demo Company"
        );

        organization.setEnabled(
                true
        );

        RoleEntity role =
                new RoleEntity();

        role.setId(
                UUID.randomUUID()
        );

        role.setName(
                "ADMIN"
        );

        UserEntity user =
                new UserEntity();

        user.setId(
                UUID.randomUUID()
        );

        user.setOrganization(
                organization
        );

        user.setEmail(
                "admin@test.com"
        );

        user.setPasswordHash(
                "hash"
        );

        user.setEnabled(
                true
        );

        user.setTokenVersion(
                tokenVersion
        );

        user.setRoles(
                new HashSet<>(
                        Set.of(
                                role
                        )
                )
        );

        return user;
    }
}
