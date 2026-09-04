package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.OrganizationModelPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates bounded audit evidence for immutable tenant policy versions. */
final class OrganizationModelPolicyAuditDetailsFactory {

    private static final int MAX_INLINE_MODEL_KEY_CHARACTERS =
            16_000;

    private OrganizationModelPolicyAuditDetailsFactory() {
    }

    static Map<String, Object> create(
            OrganizationModelPolicy policy
    ) {
        Objects.requireNonNull(
                policy,
                "policy не должен быть null"
        );

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyId", policy.id());
        details.put("organizationId", policy.organizationId());
        details.put("version", policy.version());
        details.put("enabled", policy.enabled());
        addModelKeySnapshot(
                details,
                policy.allowModelKeys(),
                policy.denyModelKeys()
        );

        if (policy.defaultModelKey() != null) {
            details.put(
                    "defaultModelKey",
                    policy.defaultModelKey()
            );
        }
        if (policy.maxInputTokens() != null) {
            details.put(
                    "maxInputTokens",
                    policy.maxInputTokens()
            );
        }
        if (policy.maxOutputTokens() != null) {
            details.put(
                    "maxOutputTokens",
                    policy.maxOutputTokens()
            );
        }
        if (policy.maxRequestCostUsd() != null) {
            details.put(
                    "maxRequestCostUsd",
                    policy.maxRequestCostUsd()
                            .toPlainString()
            );
        }
        if (policy.monthlyBudgetUsd() != null) {
            details.put(
                    "monthlyBudgetUsd",
                    policy.monthlyBudgetUsd()
                            .toPlainString()
            );
        }

        details.put(
                "budgetEnforcement",
                policy.budgetEnforcement()
        );
        details.put(
                "requireCompletePricing",
                policy.requireCompletePricing()
        );
        details.put(
                "requireNoTraining",
                policy.requireNoTraining()
        );
        details.put(
                "requireZeroDataRetention",
                policy.requireZeroDataRetention()
        );

        return Map.copyOf(details);
    }

    private static void addModelKeySnapshot(
            Map<String, Object> details,
            Set<String> allowModelKeys,
            Set<String> denyModelKeys
    ) {
        details.put(
                "allowModelKeyCount",
                allowModelKeys.size()
        );
        details.put(
                "denyModelKeyCount",
                denyModelKeys.size()
        );
        details.put(
                "allowModelKeysSha256",
                sha256ModelKeys(allowModelKeys)
        );
        details.put(
                "denyModelKeysSha256",
                sha256ModelKeys(denyModelKeys)
        );

        long characterCount = allowModelKeys.stream()
                .mapToLong(String::length)
                .sum()
                + denyModelKeys.stream()
                .mapToLong(String::length)
                .sum();
        boolean includeFullLists =
                characterCount <= MAX_INLINE_MODEL_KEY_CHARACTERS;

        details.put(
                "modelKeyListsIncluded",
                includeFullLists
        );

        if (includeFullLists) {
            details.put(
                    "allowModelKeys",
                    allowModelKeys
            );
            details.put(
                    "denyModelKeys",
                    denyModelKeys
            );
        }
    }

    private static String sha256ModelKeys(
            Set<String> values
    ) {
        String canonical = String.join(
                "\n",
                values
        );
        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                            canonical.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }
}
