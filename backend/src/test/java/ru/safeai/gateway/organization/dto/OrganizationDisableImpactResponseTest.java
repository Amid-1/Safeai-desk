package ru.safeai.gateway.organization.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationDisableImpactResponseTest {
    private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void previewCannotClaimToBeFinalOrCertain() {
        assertThatThrownBy(() -> new OrganizationDisableImpactResponse(
                ID, 1, 2, 3, 4, 5, 6, 7, false
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("предварительным");
    }

    @Test
    void retainsLegacyConstructorAndMakesAdvisoryStateExplicit() {
        var response = new OrganizationDisableImpactResponse(ID, 1, 2, 3, 4, 5);
        assertThat(response.advisoryOnly()).isTrue();
        assertThat(response.startedPhysicalAttempts()).isZero();
        assertThat(response.uncertainPhysicalAttempts()).isZero();
    }

    @Test
    void rejectsNegativePhysicalAttemptCounters() {
        assertThatThrownBy(() -> new OrganizationDisableImpactResponse(
                ID, 1, 2, 3, 4, 5, -1, 0, true
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
