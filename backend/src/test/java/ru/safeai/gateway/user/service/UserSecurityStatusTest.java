package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserSecurityStatusTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    @Test
    void preservesValidSecuritySnapshot() {
        UserSecurityStatus status =
                new UserSecurityStatus(
                        ORGANIZATION_ID,
                        true,
                        true,
                        7L,
                        13L
                );

        assertThat(status.organizationId())
                .isEqualTo(ORGANIZATION_ID);
        assertThat(status.userEnabled()).isTrue();
        assertThat(status.organizationEnabled()).isTrue();
        assertThat(status.tokenVersion()).isEqualTo(7L);
        assertThat(status.organizationAuthVersion())
                .isEqualTo(13L);
    }

    @Test
    void rejectsNullOrganizationId() {
        assertThatThrownBy(() ->
                new UserSecurityStatus(
                        null,
                        true,
                        true,
                        0L,
                        0L
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void rejectsNegativeTokenVersion() {
        assertThatThrownBy(() ->
                new UserSecurityStatus(
                        ORGANIZATION_ID,
                        true,
                        true,
                        -1L,
                        0L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenVersion");
    }

    @Test
    void rejectsNegativeOrganizationAuthVersion() {
        assertThatThrownBy(() ->
                new UserSecurityStatus(
                        ORGANIZATION_ID,
                        true,
                        true,
                        0L,
                        -1L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationAuthVersion");
    }
}
