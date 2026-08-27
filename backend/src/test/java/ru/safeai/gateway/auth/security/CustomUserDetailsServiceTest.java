package ru.safeai.gateway.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import ru.safeai.gateway.common.exception.BadRequestException;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    private static final String USER_NOT_FOUND_MESSAGE =
            "Пользователь не найден";

    private static final String PASSWORD_HASH =
            "password-hash";

    @Mock
    private UserRepository userRepository;

    @Test
    void loadsCanonicalEmailAndBuildsPasswordPrincipal() {
        UUID organizationId =
                UUID.randomUUID();

        UUID userId =
                UUID.randomUUID();

        UserEntity user =
                user(
                        userId,
                        organizationId,
                        true,
                        11L,
                        "admin@test.com",
                        "ADMIN",
                        7L
                );

        when(
                userRepository.findByEmail(
                        "admin@test.com"
                )
        ).thenReturn(
                Optional.of(
                        user
                )
        );

        CustomUserDetailsService service =
                service();

        SafeAiUserPrincipal principal =
                (SafeAiUserPrincipal)
                        service.loadUserByUsername(
                                " Admin@Test.com "
                        );

        assertThat(
                principal.getId()
        ).isEqualTo(
                userId
        );

        assertThat(
                principal.getOrganizationId()
        ).isEqualTo(
                organizationId
        );

        assertThat(
                principal.getUsername()
        ).isEqualTo(
                "admin@test.com"
        );

        assertThat(
                principal.getPassword()
        ).isEqualTo(
                PASSWORD_HASH
        );

        assertThat(
                principal.getTokenVersion()
        ).isEqualTo(
                7L
        );

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(
                11L
        );

        assertThat(
                principal.isEnabled()
        ).isTrue();

        assertThat(
                principal.getAuthorities()
        )
                .extracting(
                        GrantedAuthority::getAuthority
                )
                .containsExactly(
                        "ROLE_ADMIN"
                );

        verify(
                userRepository
        ).findByEmail(
                "admin@test.com"
        );
    }

    @Test
    void disabledOrganizationProducesDisabledPrincipal() {
        UUID organizationId =
                UUID.randomUUID();

        UserEntity user =
                user(
                        UUID.randomUUID(),
                        organizationId,
                        false,
                        3L,
                        "user@test.com",
                        "USER",
                        2L
                );

        when(
                userRepository.findByEmail(
                        "user@test.com"
                )
        ).thenReturn(
                Optional.of(
                        user
                )
        );

        CustomUserDetailsService service =
                service();

        SafeAiUserPrincipal principal =
                (SafeAiUserPrincipal)
                        service.loadUserByUsername(
                                "USER@Test.com"
                        );

        assertThat(
                principal.isEnabled()
        ).isFalse();

        assertThat(
                principal.getOrganizationId()
        ).isEqualTo(
                organizationId
        );

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(
                3L
        );

        assertThat(
                principal.getTokenVersion()
        ).isEqualTo(
                2L
        );

        assertThat(
                principal.getAuthorities()
        )
                .extracting(
                        GrantedAuthority::getAuthority
                )
                .containsExactly(
                        "ROLE_USER"
                );

        verify(
                userRepository
        ).findByEmail(
                "user@test.com"
        );
    }

    @Test
    void missingUserThrowsUsernameNotFoundWithoutEmailDisclosure() {
        when(
                userRepository.findByEmail(
                        "missing@test.com"
                )
        ).thenReturn(
                Optional.empty()
        );

        CustomUserDetailsService service =
                service();

        assertThatThrownBy(() ->
                service.loadUserByUsername(
                        " Missing@Test.com "
                )
        )
                .isInstanceOf(
                        UsernameNotFoundException.class
                )
                .hasMessage(
                        USER_NOT_FOUND_MESSAGE
                )
                .message()
                .doesNotContain(
                        "missing@test.com"
                );

        verify(
                userRepository
        ).findByEmail(
                "missing@test.com"
        );
    }

    @Test
    void invalidEmailThrowsUsernameNotFoundBeforeRepositoryLookup() {
        CustomUserDetailsService service =
                service();

        assertThatThrownBy(() ->
                service.loadUserByUsername(
                        "definitely-not-an-email"
                )
        )
                .isInstanceOf(
                        UsernameNotFoundException.class
                )
                .hasMessage(
                        USER_NOT_FOUND_MESSAGE
                )
                .hasCauseInstanceOf(
                        BadRequestException.class
                );

        verifyNoInteractions(
                userRepository
        );
    }

    private CustomUserDetailsService service() {
        return new CustomUserDetailsService(
                userRepository
        );
    }

    private UserEntity user(
            UUID userId,
            UUID organizationId,
            boolean organizationEnabled,
            long organizationAuthVersion,
            String email,
            String roleName,
            long tokenVersion
    ) {
        OrganizationEntity organization =
                organization(
                        organizationId,
                        organizationEnabled,
                        organizationAuthVersion
                );

        RoleEntity role =
                role(
                        roleName
                );

        UserEntity user =
                new UserEntity();

        user.setId(
                userId
        );

        user.setOrganization(
                organization
        );

        user.setEmail(
                email
        );

        user.setPasswordHash(
                PASSWORD_HASH
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

    private OrganizationEntity organization(
            UUID organizationId,
            boolean enabled,
            long authVersion
    ) {
        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setId(
                organizationId
        );

        organization.setEnabled(
                enabled
        );

        organization.setAuthVersion(
                authVersion
        );

        return organization;
    }

    private RoleEntity role(
            String roleName
    ) {
        RoleEntity role =
                new RoleEntity();

        role.setId(
                UUID.randomUUID()
        );

        role.setName(
                roleName
        );

        return role;
    }
}