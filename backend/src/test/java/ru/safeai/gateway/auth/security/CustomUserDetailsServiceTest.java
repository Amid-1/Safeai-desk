package ru.safeai.gateway.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void loadsCanonicalEmailAndBuildsPasswordPrincipal() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(organizationId);
        organization.setEnabled(true);

        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName("ADMIN");

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganization(organization);
        user.setEmail("admin@test.com");
        user.setPasswordHash("password-hash");
        user.setEnabled(true);
        user.setTokenVersion(7L);
        user.setRoles(new HashSet<>(Set.of(role)));

        when(userRepository.findByEmail("admin@test.com"))
                .thenReturn(Optional.of(user));

        CustomUserDetailsService service =
                new CustomUserDetailsService(userRepository);

        SafeAiUserPrincipal principal =
                (SafeAiUserPrincipal) service.loadUserByUsername(
                        " Admin@Test.com "
                );

        assertThat(principal.getId()).isEqualTo(userId);
        assertThat(principal.getOrganizationId())
                .isEqualTo(organizationId);
        assertThat(principal.getUsername()).isEqualTo("admin@test.com");
        assertThat(principal.getPassword()).isEqualTo("password-hash");
        assertThat(principal.getTokenVersion()).isEqualTo(7L);
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");

        verify(userRepository).findByEmail("admin@test.com");
    }

    @Test
    void missingUserThrowsUsernameNotFound() {
        when(userRepository.findByEmail("missing@test.com"))
                .thenReturn(Optional.empty());

        CustomUserDetailsService service =
                new CustomUserDetailsService(userRepository);

        assertThatThrownBy(() -> service.loadUserByUsername(
                "missing@test.com"
        ))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("missing@test.com");
    }
}
