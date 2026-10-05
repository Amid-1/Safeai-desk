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
    void modelKeysAreNormalizedSortedAndImmutable() {
        LinkedHashSet<String> source = new LinkedHashSet<>();
        source.add("z:model");
        source.add(" A:MODEL ");
        source.add("m:model");

        Set<String> normalized =
                OrganizationModelPolicyRules.normalizeModelKeys(
                        source
                );

        assertThat(normalized)
                .containsExactly(
                        "a:model",
                        "m:model",
                        "z:model"
                );

        assertThatThrownBy(() ->
                normalized.add("x:model")
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalDuplicateModelKeysAreRejectedInsteadOfSilentlyDeduplicated() {
        LinkedHashSet<String> source = new LinkedHashSet<>();
        source.add(" A:MODEL ");
        source.add("a:model");

        assertThatThrownBy(() ->
                OrganizationModelPolicyRules.normalizeModelKeys(
                        source
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("дублирующийся canonical modelKey")
                .hasMessageContaining("a:model");
    }

    @Test
    void emptyAndNullModelKeySetsNormalizeToImmutableEmptySet() {
        Set<String> fromNull =
                OrganizationModelPolicyRules.normalizeModelKeys(
                        null
                );
        Set<String> fromEmpty =
                OrganizationModelPolicyRules.normalizeModelKeys(
                        Set.of()
                );

        assertThat(fromNull).isEmpty();
        assertThat(fromEmpty).isEmpty();

        assertThatThrownBy(() ->
                fromNull.add("x:model")
        ).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() ->
                fromEmpty.add("x:model")
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
    void defaultModelCannotBePresentInDenyList() {
        assertThatThrownBy(() ->
                OrganizationModelPolicyRules.validate(
                        Set.of(),
                        Set.of("openai:gpt-test"),
                        "openai:gpt-test",
                        null,
                        null,
                        null,
                        null,
                        BudgetEnforcement.SOFT
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("denylist");
    }

    @Test
    void nonPositiveInputTokenLimitIsRejected() {
        assertThatThrownBy(() ->
                OrganizationModelPolicyRules.validate(
                        Set.of(),
                        Set.of(),
                        null,
                        0,
                        null,
                        null,
                        null,
                        BudgetEnforcement.SOFT
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maxInputTokens");
    }

    @Test
    void nonPositiveOutputTokenLimitIsRejected() {
        assertThatThrownBy(() ->
                OrganizationModelPolicyRules.validate(
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        0,
                        null,
                        null,
                        BudgetEnforcement.SOFT
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maxOutputTokens");
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
        BigDecimal normalized =
                OrganizationModelPolicyRules.normalizeMoney(
                        new BigDecimal("1.5"),
                        "maxRequestCostUsd"
                );

        assertThat(normalized)
                .isEqualByComparingTo("1.500000000000");
        assertThat(normalized.scale())
                .isEqualTo(12);
        assertThat(normalized.toPlainString())
                .isEqualTo("1.500000000000");
    }

    @Test
    void negativePolicyMoneyIsRejected() {
        assertThatThrownBy(() ->
                OrganizationModelPolicyRules.normalizeMoney(
                        new BigDecimal("-0.000000000001"),
                        "monthlyBudgetUsd"
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("monthlyBudgetUsd");
    }

    @Test
    void nullableDefaultModelKeyUsesTheSameCanonicalModelKeyRules() {
        assertThat(
                OrganizationModelPolicyRules.normalizeNullableModelKey(
                        " OpenAI:GPT-Test "
                )
        ).isEqualTo("openai:gpt-test");

        assertThat(
                OrganizationModelPolicyRules.normalizeNullableModelKey(
                        "   "
                )
        ).isNull();

        assertThat(
                OrganizationModelPolicyRules.normalizeNullableModelKey(
                        null
                )
        ).isNull();
    }
}
