package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.model.domain.BudgetEnforcement;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationModelPolicyRulesTest {

    @Test
    void modelKeysAreNormalizedDeduplicatedSortedAndImmutable() {
        LinkedHashSet<String> source = new LinkedHashSet<>();
        source.add("z:model");
        source.add(" A:MODEL ");
        source.add("a:model");

        Set<String> normalized =
                OrganizationModelPolicyRules.normalizeModelKeys(
                        source
                );

        assertThat(normalized)
                .containsExactly(
                        "a:model",
                        "z:model"
                );

        assertThatThrownBy(() ->
                normalized.add("x:model")
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void overlappingAllowAndDenyListsAreRejected() {
        assertThatThrownBy(() ->
                OrganizationModelPolicyRules.validate(
                        Set.of("openai:gpt-test"),
                        Set.of("openai:gpt-test"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        BudgetEnforcement.SOFT
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("пересекаются");
    }

    @Test
    void defaultModelMustBeVisibleThroughNonEmptyAllowList() {
        assertThatThrownBy(() ->
                OrganizationModelPolicyRules.validate(
                        Set.of("openai:gpt-a"),
                        Set.of(),
                        "openai:gpt-b",
                        null,
                        null,
                        null,
                        null,
                        BudgetEnforcement.SOFT
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void nullBudgetEnforcementFailsClosedAtServiceBoundary() {
        assertThatThrownBy(() ->
                OrganizationModelPolicyRules.validate(
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("budgetEnforcement");
    }

    @Test
    void policyMoneyIsCanonicalizedToDatabaseScale() {
        assertThat(OrganizationModelPolicyRules.normalizeMoney(
                new BigDecimal("1.5"),
                "maxRequestCostUsd"
        )).isEqualByComparingTo("1.500000000000");
    }
}
