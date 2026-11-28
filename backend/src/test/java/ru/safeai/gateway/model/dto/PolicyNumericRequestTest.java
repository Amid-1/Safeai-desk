package ru.safeai.gateway.model.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyNumericRequestTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0", "1e3", "\"1000\"", "\"0x100\"", "true", "[]", "2147483648"})
    void rejectsTokenCoercionAndOverflowForBothFields(String literal) {
        assertThatThrownBy(() -> read(literal, "null", "null", "null"))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> read("null", literal, "null", "null"))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void preservesOptionalTokenLimitsAndExactUsdAmounts() {
        var request = read("1", "2147483647", "\"0.000000000001\"",
                "\"999999999999999999.123456789012\"");
        assertThat(request.maxInputTokens()).isEqualTo(1);
        assertThat(request.maxOutputTokens()).isEqualTo(Integer.MAX_VALUE);
        assertThat(request.maxRequestCostUsd()).isEqualTo(new BigDecimal("0.000000000001"));
        assertThat(request.monthlyBudgetUsd()).isEqualTo(new BigDecimal("999999999999999999.123456789012"));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request)).isEmpty();
            var optional = read("null", "null", "null", "\"0\"");
            assertThat(validator.validate(optional)).isEmpty();
            assertThat(optional.maxInputTokens()).isNull();
            assertThat(optional.maxOutputTokens()).isNull();
            assertThat(optional.maxRequestCostUsd()).isNull();
            assertThat(optional.monthlyBudgetUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void rejectsNonPositiveTokensAndOutOfRangeMoneyDuringValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(read("0", "-1", "\"-0.01\"", "\"0.0000000000001\"")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("maxInputTokens", "maxOutputTokens",
                            "maxRequestCostUsd", "monthlyBudgetUsd");
            assertThat(validator.validate(read("null", "null", "\"1000000000000000000\"", "null")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("maxRequestCostUsd");
        }
    }

    private static CreateOrganizationModelPolicyVersionRequest read(
            String input, String output, String requestCost, String monthlyBudget
    ) {
        return MAPPER.readValue(payload(input, output, requestCost, monthlyBudget),
                CreateOrganizationModelPolicyVersionRequest.class);
    }

    @Test
    void missingAndNullAcknowledgmentNeverImplyConsent() {
        assertThat(read("null", "null", "null", "null").acknowledgeLockout()).isFalse();
        assertThat(readWithAcknowledgment("null").acknowledgeLockout()).isFalse();
        assertThat(readWithAcknowledgment("false").acknowledgeLockout()).isFalse();
        assertThat(readWithAcknowledgment("true").acknowledgeLockout()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "0", "1.0", "\"true\"", "\"false\"", "\"1\"", "\"\"", "[]", "{}"})
    void rejectsCoercionOfLockoutAcknowledgment(String literal) {
        assertThatThrownBy(() -> readWithAcknowledgment(literal)).isInstanceOf(JacksonException.class);
    }

    private static CreateOrganizationModelPolicyVersionRequest readWithAcknowledgment(String literal) {
        String json = payload("null", "null", "null", "null").stripTrailing();
        return MAPPER.readValue(json.substring(0, json.length() - 1)
                        + ",\"acknowledgeLockout\":" + literal + "}",
                CreateOrganizationModelPolicyVersionRequest.class);
    }

    private static String payload(String input, String output, String requestCost, String monthlyBudget) {
        return """
                {
                  "expectedPreviousVersion": 0,
                  "enabled": true,
                  "allowModelKeys": [],
                  "denyModelKeys": [],
                  "defaultModelKey": null,
                  "maxInputTokens": %s,
                  "maxOutputTokens": %s,
                  "maxRequestCostUsd": %s,
                  "monthlyBudgetUsd": %s,
                  "budgetEnforcement": "SOFT",
                  "requireCompletePricing": false,
                  "requireNoTraining": false,
                  "requireZeroDataRetention": false
                }
                """.formatted(input, output, requestCost, monthlyBudget);
    }
}
