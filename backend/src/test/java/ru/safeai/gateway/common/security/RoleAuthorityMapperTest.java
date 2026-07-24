package ru.safeai.gateway.common.security;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleAuthorityMapperTest {

    @Test
    void mapsKnownRolesInStableOrder() {
        assertThat(RoleAuthorityMapper.toAuthorities(
                List.of("user", "ROLE_ADMIN")
        ))
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactly(
                        "ROLE_ADMIN",
                        "ROLE_USER"
                );
    }

    @Test
    void unknownRoleFailsFast() {
        assertThatThrownBy(() ->
                RoleAuthorityMapper.toAuthorities(
                        List.of("ROOT")
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown role");
    }

    @Test
    void nullRoleFailsFast() {
        List<@Nullable String> roles = new ArrayList<>();
        roles.add("USER");
        roles.add(null);

        assertThatThrownBy(() ->
                RoleAuthorityMapper.toAuthorities(roles)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("role element не должен быть null");
    }
}