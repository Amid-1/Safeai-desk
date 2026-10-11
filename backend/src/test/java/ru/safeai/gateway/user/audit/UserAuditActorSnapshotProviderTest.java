package ru.safeai.gateway.user.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAuditActorSnapshotProviderTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ORGANIZATION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserEntity userEntity;

    @Test
    void resolvesCanonicalActorFromTenantScopedUser() {
        stubUser("Admin Name");
        AuditActor actor = provider().resolve(principal(), null);

        assertThat(actor.userId()).isEqualTo(USER_ID);
        assertThat(actor.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(actor.email()).isEqualTo("admin@example.com");
        assertThat(actor.displayName()).isEqualTo("Admin Name");
        verify(userRepository).findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID);
    }

    @Test
    void explicitDisplayNameOverridesCanonicalNameOnly() {
        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(userEntity));
        when(userEntity.getEmail()).thenReturn("admin@example.com");

        AuditActor actor = provider().resolve(principal(), "Explicit Name");

        assertThat(actor.email()).isEqualTo("admin@example.com");
        assertThat(actor.displayName()).isEqualTo("Explicit Name");
        assertThat(actor.userId()).isEqualTo(USER_ID);
        assertThat(actor.organizationId()).isEqualTo(ORGANIZATION_ID);
    }

    @Test
    void missingTenantScopedUserFallsBackToNonPiiPrincipal() {
        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.empty());

        AuditActor actor = provider().resolve(principal(), null);

        assertThat(actor).isEqualTo(AuditActor.fromPrincipal(principal(), null));
        assertThat(actor.email()).isNull();
        assertThat(actor.displayName()).isNull();
    }

    @Test
    void repositoryFailureIsNotSilentlyDowngradedToFallback() {
        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> provider().resolve(principal(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void nullPrincipalIsRejectedBeforeDatabaseCall() {
        assertThatThrownBy(() -> provider().resolve(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("currentUser не должен быть null");
        verifyNoInteractions(userRepository);
    }

    private void stubUser(String fullName) {
        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(userEntity));
        when(userEntity.getEmail()).thenReturn("admin@example.com");
        when(userEntity.getFullName()).thenReturn(fullName);
    }

    private UserAuditActorSnapshotProvider provider() {
        return new UserAuditActorSnapshotProvider(userRepository);
    }

    private SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}
