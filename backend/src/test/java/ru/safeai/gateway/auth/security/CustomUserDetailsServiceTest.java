package ru.safeai.gateway.auth.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private UserRepository userRepository;

    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void setUp() {
        customUserDetailsService = new CustomUserDetailsService(userRepository);
    }

    @Test
    void loadUserByUsername_shouldReturnPrincipalWhenUserExists() {
        UserEntity user = userEntity();

        when(userRepository.findByEmailIgnoreCase("admin@test.com"))
                .thenReturn(Optional.of(user));

        SafeAiUserPrincipal principal =
                (SafeAiUserPrincipal) customUserDetailsService.loadUserByUsername(" Admin@Test.COM ");

        assertThat(principal.getId()).isEqualTo(USER_ID);
        assertThat(principal.getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(principal.getEmail()).isEqualTo("admin@test.com");
        assertThat(principal.getPassword()).isEqualTo("encoded-password");
        assertThat(principal.isEnabled()).isTrue();

        assertThat(principal.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void loadUserByUsername_shouldThrowUsernameNotFoundWhenUserDoesNotExist() {
        when(userRepository.findByEmailIgnoreCase("missing@test.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                customUserDetailsService.loadUserByUsername("missing@test.com")
        )
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("Пользователь не найден");
    }

    @Test
    void loadUserByUsername_shouldReturnDisabledPrincipalWhenOrganizationDisabled() {
        UserEntity user = userEntity();
        user.getOrganization().setEnabled(false);

        when(userRepository.findByEmailIgnoreCase("admin@test.com"))
                .thenReturn(Optional.of(user));

        SafeAiUserPrincipal principal =
                (SafeAiUserPrincipal) customUserDetailsService.loadUserByUsername("admin@test.com");

        assertThat(principal.isEnabled()).isFalse();
    }

    @Test
    void loadUserByUsername_shouldReturnDisabledPrincipalWhenUserDisabled() {
        UserEntity user = userEntity();
        user.setEnabled(false);

        when(userRepository.findByEmailIgnoreCase("admin@test.com"))
                .thenReturn(Optional.of(user));

        SafeAiUserPrincipal principal =
                (SafeAiUserPrincipal) customUserDetailsService.loadUserByUsername("admin@test.com");

        assertThat(principal.isEnabled()).isFalse();
    }

    private UserEntity userEntity() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Demo Company");
        organization.setEnabled(true);
        organization.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));

        RoleEntity role = new RoleEntity();
        role.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        role.setName("ADMIN");

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setOrganization(organization);
        user.setEmail("admin@test.com");
        user.setPasswordHash("encoded-password");
        user.setFullName("Demo Admin");
        user.setEnabled(true);
        user.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        user.setRoles(Set.of(role));
        user.setTokenVersion(0L);

        return user;
    }
}