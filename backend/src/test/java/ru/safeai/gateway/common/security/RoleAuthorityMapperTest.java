package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleAuthorityMapperTest {

    @Test
    void toAuthorities_shouldAddRolePrefix() {
        Set<SimpleGrantedAuthority> authorities =
                RoleAuthorityMapper.toAuthorities(List.of("ADMIN"));

        assertThat(authorities)
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void toAuthorities_shouldNotDuplicateRolePrefix() {
        Set<SimpleGrantedAuthority> authorities =
                RoleAuthorityMapper.toAuthorities(List.of("ROLE_ADMIN"));

        assertThat(authorities)
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void toAuthorities_shouldNormalizeRolesToUppercase() {
        Set<SimpleGrantedAuthority> authorities =
                RoleAuthorityMapper.toAuthorities(List.of("admin", "user"));

        assertThat(authorities)
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void toAuthorities_shouldReturnEmptySetWhenRolesAreNull() {
        assertThat(RoleAuthorityMapper.toAuthorities(null)).isEmpty();
    }

    @Test
    void toAuthorities_shouldIgnoreBlankRoles() {
        Set<SimpleGrantedAuthority> authorities =
                RoleAuthorityMapper.toAuthorities(List.of("ADMIN", "   "));

        assertThat(authorities)
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void toAuthorities_shouldIgnoreNullRoles() {
        List<String> roles = Arrays.asList("ADMIN", null, "   ");

        Set<SimpleGrantedAuthority> authorities =
                RoleAuthorityMapper.toAuthorities(roles);

        assertThat(authorities)
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void toAuthorities_shouldThrowWhenRoleIsUnknown() {
        assertThatThrownBy(() -> RoleAuthorityMapper.toAuthorities(List.of("ROOT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown role");
    }

    @Test
    void toRoleNames_shouldReturnSortedRoleNamesWithoutPrefix() {
        List<String> roles = RoleAuthorityMapper.toRoleNames(List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
        ));

        assertThat(roles)
                .containsExactly("ADMIN", "SUPER_ADMIN", "USER");
    }

    @Test
    void toRoleNames_shouldThrowWhenAuthorityContainsUnknownRole() {
        assertThatThrownBy(() -> RoleAuthorityMapper.toRoleNames(List.of(
                new SimpleGrantedAuthority("ROLE_ROOT")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown role");
    }
}