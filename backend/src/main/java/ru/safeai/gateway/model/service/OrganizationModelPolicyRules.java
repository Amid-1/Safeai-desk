package ru.safeai.gateway.model.service;

import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.model.domain.BudgetEnforcement;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Pure canonicalization and semantic validation for tenant model policies. */
final class OrganizationModelPolicyRules {

    private OrganizationModelPolicyRules() {
    }

    static BigDecimal normalizeMoney(
            BigDecimal value,
            String field
    ) {
        return ModelControlPlaneNumericValidation
                .normalizeNonNegativeNumeric30Scale12(
                        value,
                        field
                );
    }

    static void validate(
            Set<String> allow,
            Set<String> deny,
            String defaultModelKey,
            Integer maxInputTokens,
            Integer maxOutputTokens,
            BigDecimal maxRequestCostUsd,
            BigDecimal monthlyBudgetUsd,
            BudgetEnforcement budgetEnforcement
    ) {
        if (allow.size() > 200 || deny.size() > 200) {
            throw new BadRequestException(
                    "Model allow/deny list не должен превышать 200 элементов"
            );
        }

        Set<String> overlap = new LinkedHashSet<>(allow);
        overlap.retainAll(deny);
        if (!overlap.isEmpty()) {
            throw new BadRequestException(
                    "Model allowlist и denylist пересекаются: "
                            + overlap
            );
        }

        if (defaultModelKey != null) {
            if (deny.contains(defaultModelKey)) {
                throw new BadRequestException(
                        "defaultModelKey не может находиться в denylist"
                );
            }
            if (!allow.isEmpty()
                    && !allow.contains(defaultModelKey)) {
                throw new BadRequestException(
                        "defaultModelKey должен входить в непустой allowlist"
                );
            }
        }

        if (maxInputTokens != null && maxInputTokens <= 0) {
            throw new BadRequestException(
                    "maxInputTokens должен быть положительным"
            );
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new BadRequestException(
                    "maxOutputTokens должен быть положительным"
            );
        }

        ModelControlPlaneNumericValidation
                .requireNonNegativeNumeric30Scale12(
                        maxRequestCostUsd,
                        "maxRequestCostUsd"
                );
        ModelControlPlaneNumericValidation
                .requireNonNegativeNumeric30Scale12(
                        monthlyBudgetUsd,
                        "monthlyBudgetUsd"
                );

        if (budgetEnforcement == null) {
            throw new BadRequestException(
                    "budgetEnforcement не должен быть null"
            );
        }
    }

    static Set<String> normalizeModelKeys(
            Set<String> values
    ) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }

        TreeSet<String> result = new TreeSet<>();
        for (String value : values) {
            result.add(
                    ModelCatalogService.normalizeModelKey(
                            value
                    )
            );
        }
        return Collections.unmodifiableSet(result);
    }

    static String normalizeNullableModelKey(
            String value
    ) {
        return value == null || value.isBlank()
                ? null
                : ModelCatalogService.normalizeModelKey(value);
    }
}
