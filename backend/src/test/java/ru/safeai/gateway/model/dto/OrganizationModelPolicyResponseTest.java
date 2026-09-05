package ru.safeai.gateway.model.dto;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.domain.BudgetEnforcement;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationModelPolicyResponseTest {

    @Test
    void unconfiguredPolicyIsExplicitlyDisabled() {
        UUID organizationId = UUID.fromString(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        );

        OrganizationModelPolicyResponse response =
                OrganizationModelPolicyResponse.unconfigured(
                        organizationId
                );

        assertThat(response.configured()).isFalse();
        assertThat(response.version()).isZero();
        assertThat(response.enabled()).isFalse();
        assertThat(response.organizationId()).isEqualTo(organizationId);
        assertThat(response.allowModelKeys()).isEmpty();
        assertThat(response.denyModelKeys()).isEmpty();
        assertThat(response.budgetEnforcement())
                .isEqualTo(BudgetEnforcement.SOFT);
    }
}
