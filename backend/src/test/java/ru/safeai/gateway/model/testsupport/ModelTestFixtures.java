package ru.safeai.gateway.model.testsupport;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ModelTestFixtures {

    public static final Instant NOW =
            Instant.parse("2026-08-28T12:00:00Z");

    public static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    public static final UUID USER_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    public static final UUID ORGANIZATION_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    public static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222223");

    public static final UUID CHAT_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

    public static final UUID TURN_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");

    public static final UUID CLIENT_REQUEST_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");

    public static final UUID CATALOG_ENTRY_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");

    public static final UUID POLICY_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    public static final String REQUEST_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private ModelTestFixtures() {
    }

    public static SafeAiUserPrincipal userPrincipal() {
        return principal(
                ORGANIZATION_ID,
                "ROLE_USER"
        );
    }

    public static SafeAiUserPrincipal adminPrincipal() {
        return principal(
                ORGANIZATION_ID,
                "ROLE_ADMIN"
        );
    }

    public static SafeAiUserPrincipal superAdminPrincipal() {
        return principal(
                ORGANIZATION_ID,
                "ROLE_SUPER_ADMIN"
        );
    }

    public static SafeAiUserPrincipal principal(
            UUID organizationId,
            String authority
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                organizationId,
                0L,
                0L,
                List.of(
                        new SimpleGrantedAuthority(authority)
                )
        );
    }

    public static RuntimeModelStatusResponse freeRuntime() {
        return runtime(
                "FREE",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );
    }

    public static RuntimeModelStatusResponse configuredRuntime() {
        return runtime(
                "CONFIGURED",
                new BigDecimal("1.000000000000"),
                new BigDecimal("4.000000000000"),
                "pricing-2026-08"
        );
    }

    public static RuntimeModelStatusResponse runtime(
            String pricingStatus,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            String pricingVersion
    ) {
        return new RuntimeModelStatusResponse(
                "openai",
                "gpt-test",
                true,
                "SINGLE_PROVIDER_STATIC",
                32_000,
                4_096,
                false,
                false,
                false,
                "NOT_DECLARED",
                "NOT_PROBED",
                pricingStatus,
                inputPrice,
                outputPrice,
                pricingVersion
        );
    }

    public static ModelCatalogEntry freeEntry() {
        return entry(
                ModelLifecycle.ACTIVE,
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Set.of(),
                ModelRetentionStatus.NOT_DECLARED,
                ModelTrainingUseStatus.NOT_DECLARED
        );
    }

    public static ModelCatalogEntry configuredEntry() {
        return entry(
                ModelLifecycle.ACTIVE,
                ModelPricingStatus.CONFIGURED,
                true,
                new BigDecimal("1.000000000000"),
                new BigDecimal("4.000000000000"),
                Set.of(),
                ModelRetentionStatus.NOT_DECLARED,
                ModelTrainingUseStatus.NOT_DECLARED
        );
    }

    public static ModelCatalogEntry entry(
            ModelLifecycle lifecycle,
            ModelPricingStatus pricingStatus,
            boolean pricingComplete,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            Set<ModelCapability> capabilities,
            ModelRetentionStatus retentionStatus,
            ModelTrainingUseStatus trainingUseStatus
    ) {
        return new ModelCatalogEntry(
                CATALOG_ENTRY_ID,
                "openai:gpt-test",
                2,
                "openai",
                "gpt-test",
                "GPT Test",
                lifecycle,
                32_000,
                4_096,
                capabilities,
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                retentionStatus,
                retentionStatus == ModelRetentionStatus.ZERO_DATA_RETENTION
                        ? 0
                        : null,
                trainingUseStatus,
                pricingStatus,
                pricingComplete,
                inputPrice,
                null,
                null,
                outputPrice,
                "{}",
                pricingStatus == ModelPricingStatus.CONFIGURED
                        ? "pricing-2026-08"
                        : null,
                NOW.minusSeconds(60),
                ModelCatalogSource.MANUAL,
                USER_ID,
                NOW.minusSeconds(120)
        );
    }

    public static OrganizationModelPolicy hardPolicy() {
        return policy(
                true,
                Set.of(),
                Set.of(),
                null,
                32_000,
                4_096,
                new BigDecimal("10.000000000000"),
                new BigDecimal("100.000000000000"),
                BudgetEnforcement.HARD,
                true,
                false,
                false
        );
    }

    public static OrganizationModelPolicy policy(
            boolean enabled,
            Set<String> allow,
            Set<String> deny,
            String defaultModelKey,
            Integer maxInputTokens,
            Integer maxOutputTokens,
            BigDecimal maxRequestCostUsd,
            BigDecimal monthlyBudgetUsd,
            BudgetEnforcement enforcement,
            boolean requireCompletePricing,
            boolean requireNoTraining,
            boolean requireZeroDataRetention
    ) {
        return new OrganizationModelPolicy(
                POLICY_ID,
                ORGANIZATION_ID,
                3,
                enabled,
                allow,
                deny,
                defaultModelKey,
                maxInputTokens,
                maxOutputTokens,
                maxRequestCostUsd,
                monthlyBudgetUsd,
                enforcement,
                requireCompletePricing,
                requireNoTraining,
                requireZeroDataRetention,
                USER_ID,
                NOW.minusSeconds(300)
        );
    }

    public static ModelRouteRequest routeRequest(
            String requestedModelKey,
            Set<ModelCapability> capabilities
    ) {
        return new ModelRouteRequest(
                ORGANIZATION_ID,
                USER_ID,
                CHAT_ID,
                TURN_ID,
                CLIENT_REQUEST_ID,
                REQUEST_HASH,
                requestedModelKey,
                "hello",
                List.of(),
                capabilities
        );
    }
}
