package ru.safeai.gateway.model.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
                Validation.buildDefaultValidatorFactory();
        validator =
                validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void catalogRequestRejectsBlankIdentityAndInvalidMoneyScale() {
        CreateModelCatalogVersionRequest request =
                catalogRequest(
                        " ",
                        " ",
                        " ",
                        " ",
                        null,
                        0,
                        0,
                        null,
                        null,
                        ModelPricingStatus.INCOMPLETE,
                        false,
                        new BigDecimal(
                                "0.0000000000001"
                        ),
                        null,
                        null
                );

        assertThat(validator.validate(request))
                .extracting(violation ->
                        violation.getPropertyPath()
                                .toString()
                )
                .contains(
                        "modelKey",
                        "provider",
                        "providerModelId",
                        "displayName",
                        "lifecycle",
                        "maxInputTokens",
                        "maxOutputTokens",
                        "retentionStatus",
                        "trainingUseStatus",
                        "inputUsdPer1mTokens"
                );
    }

    @Test
    void policyRequestRejectsNullEnforcementAndInvalidNumericScale() {
        CreateOrganizationModelPolicyVersionRequest request =
                new CreateOrganizationModelPolicyVersionRequest(
                        0,
                        true,
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        null,
                        new BigDecimal(
                                "0.0000000000001"
                        ),
                        null,
                        null,
                        false,
                        false,
                        false
                );

        assertThat(validator.validate(request))
                .extracting(violation ->
                        violation.getPropertyPath()
                                .toString()
                )
                .contains(
                        "budgetEnforcement",
                        "maxRequestCostUsd"
                );
    }

    @Test
    void policyRequestRejectsBlankModelKeysAtHttpValidationBoundary() {
        CreateOrganizationModelPolicyVersionRequest request =
                new CreateOrganizationModelPolicyVersionRequest(
                        0,
                        true,
                        Set.of("   "),
                        Set.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        BudgetEnforcement.SOFT,
                        false,
                        false,
                        false
                );

        assertThat(validator.validate(request))
                .extracting(violation ->
                        violation.getPropertyPath()
                                .toString()
                )
                .anyMatch(path ->
                        path.startsWith(
                                "allowModelKeys"
                        )
                );
    }

    @Test
    void validCatalogAndPolicyRequestsPassBeanValidation() {
        CreateModelCatalogVersionRequest catalog =
                catalogRequest(
                        "openai:gpt-test",
                        "openai",
                        "gpt-test",
                        "GPT Test",
                        ModelLifecycle.ACTIVE,
                        32_000,
                        4_096,
                        ModelRetentionStatus.NOT_DECLARED,
                        ModelTrainingUseStatus.NOT_DECLARED,
                        ModelPricingStatus.CONFIGURED,
                        true,
                        new BigDecimal(
                                "1.000000000000"
                        ),
                        new BigDecimal(
                                "4.000000000000"
                        ),
                        "pricing-v1"
                );

        CreateOrganizationModelPolicyVersionRequest policy =
                new CreateOrganizationModelPolicyVersionRequest(
                        0,
                        true,
                        Set.of("openai:gpt-test"),
                        Set.of(),
                        "openai:gpt-test",
                        8_000,
                        1_000,
                        new BigDecimal(
                                "5.000000000000"
                        ),
                        new BigDecimal(
                                "50.000000000000"
                        ),
                        BudgetEnforcement.HARD,
                        true,
                        true,
                        true
                );

        assertThat(validator.validate(catalog))
                .isEmpty();
        assertThat(validator.validate(policy))
                .isEmpty();
    }

    private static CreateModelCatalogVersionRequest catalogRequest(
            String modelKey,
            String provider,
            String providerModelId,
            String displayName,
            ModelLifecycle lifecycle,
            int maxInputTokens,
            int maxOutputTokens,
            ModelRetentionStatus retentionStatus,
            ModelTrainingUseStatus trainingUseStatus,
            ModelPricingStatus pricingStatus,
            boolean pricingComplete,
            BigDecimal inputUsdPer1mTokens,
            BigDecimal outputUsdPer1mTokens,
            String pricingVersion
    ) {
        return new CreateModelCatalogVersionRequest(
                modelKey,
                provider,
                providerModelId,
                displayName,
                lifecycle,
                maxInputTokens,
                maxOutputTokens,
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                retentionStatus,
                null,
                trainingUseStatus,
                pricingStatus,
                pricingComplete,
                inputUsdPer1mTokens,
                null,
                null,
                outputUsdPer1mTokens,
                "{}",
                pricingVersion,
                null,
                0
        );
    }
}
