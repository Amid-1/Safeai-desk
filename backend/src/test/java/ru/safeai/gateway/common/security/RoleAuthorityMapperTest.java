package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleAuthorityMapperTest {

    @Test
    void mapsRoleNamesToDeterministicAuthorities() {
        assertThat(
                RoleAuthorityMapper
                        .toAuthorities(
                                List.of(
                                        "user",
                                        "ADMIN",
                                        "ROLE_USER"
                                )
                        )
        )
                .extracting(
                        GrantedAuthority
                                ::getAuthority
                )
                .containsExactly(
                        "ROLE_ADMIN",
                        "ROLE_USER"
                );
    }

    @Test
    void mapsAuthoritiesToDeterministicRoleNames() {
        assertThat(
                RoleAuthorityMapper
                        .toRoleNames(
                                Set.of(
                                        new SimpleGrantedAuthority(
                                                "ROLE_USER"
                                        ),
                                        new SimpleGrantedAuthority(
                                                "ROLE_ADMIN"
                                        )
                                )
                        )
        ).containsExactly(
                "ADMIN",
                "USER"
        );
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() ->
                RoleAuthorityMapper
                        .toAuthorities(
                                List.of(
                                        "UNKNOWN"
                                )
                        )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }

    @Test
    void rejectsAuthorityWithNullName() {
        GrantedAuthority invalidAuthority =
                () -> null;

        assertThatThrownBy(() ->
                RoleAuthorityMapper
                        .toRoleNames(
                                List.of(
                                        invalidAuthority
                                )
                        )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "getAuthority"
                );
    }
}
