package ru.safeai.gateway.ai.execution;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable identity of the provider target selected before I/O. The static
 * references are deliberate compatibility identities; Registry versions will
 * replace their values without changing the execution contract.
 */
public record ProviderExecutionTarget(
        String providerType,
        String providerConfigurationRef,
        String providerConfigurationVersion,
        String deploymentRef,
        String deploymentVersion,
        String requestedPhysicalModel,
        Set<String> allowedResolvedModels
) {
    private static final int MAX_PROVIDER_TYPE_LENGTH = 32;
    private static final int MAX_REFERENCE_LENGTH = 255;
    private static final int MAX_VERSION_LENGTH = 64;
    private static final int MAX_MODEL_LENGTH = 100;

    public ProviderExecutionTarget {
        providerType = required(
                providerType,
                MAX_PROVIDER_TYPE_LENGTH,
                "providerType"
        );
        providerConfigurationRef = required(
                providerConfigurationRef,
                MAX_REFERENCE_LENGTH,
                "providerConfigurationRef"
        );
        providerConfigurationVersion = required(
                providerConfigurationVersion,
                MAX_VERSION_LENGTH,
                "providerConfigurationVersion"
        );
        deploymentRef = required(
                deploymentRef,
                MAX_REFERENCE_LENGTH,
                "deploymentRef"
        );
        deploymentVersion = required(
                deploymentVersion,
                MAX_VERSION_LENGTH,
                "deploymentVersion"
        );
        requestedPhysicalModel = required(
                requestedPhysicalModel,
                MAX_MODEL_LENGTH,
                "requestedPhysicalModel"
        );
        allowedResolvedModels = allowedResolvedModels == null
                ? Set.of(requestedPhysicalModel)
                : allowedResolvedModels.stream()
                .map(value -> required(
                        value,
                        MAX_MODEL_LENGTH,
                        "allowedResolvedModels"
                ))
                .collect(Collectors.toUnmodifiableSet());

        if (allowedResolvedModels.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedResolvedModels не должен быть пустым"
            );
        }
    }

    public static ProviderExecutionTarget staticTarget(String provider, String model) {
        String normalizedProvider = required(
                provider,
                MAX_PROVIDER_TYPE_LENGTH,
                "provider"
        );
        String normalizedModel = required(
                model,
                MAX_MODEL_LENGTH,
                "model"
        );
        return new ProviderExecutionTarget(
                normalizedProvider,
                "static:" + normalizedProvider,
                "static-v1",
                "static:" + normalizedProvider + ":" + normalizedModel,
                "static-v1",
                normalizedModel,
                Set.of(normalizedModel)
        );
    }

    public void requireApprovedResolvedModel(String actualModel) {
        if (!allowedResolvedModels.contains(required(
                actualModel,
                MAX_MODEL_LENGTH,
                "actualModel"
        ))) {
            throw mismatch(
                    actualModel,
                    null
            );
        }
    }

    public void requireApprovedResponse(
            String providerRequestedModel,
            String actualModel,
            String providerRequestId
    ) {
        String normalizedRequested = required(
                providerRequestedModel,
                MAX_MODEL_LENGTH,
                "providerRequestedModel"
        );

        if (!requestedPhysicalModel.equals(normalizedRequested)) {
            throw mismatch(
                    normalizedRequested,
                    providerRequestId
            );
        }

        String normalizedActual = required(
                actualModel,
                MAX_MODEL_LENGTH,
                "actualModel"
        );

        if (!allowedResolvedModels.contains(normalizedActual)) {
            throw mismatch(
                    normalizedActual,
                    providerRequestId
            );
        }
    }

    private ResolvedModelMismatchException mismatch(
            String actualModel,
            String providerRequestId
    ) {
        return new ResolvedModelMismatchException(
                providerType,
                requestedPhysicalModel,
                actualModel,
                providerRequestId
        );
    }

    private static String required(
            String value,
            int maxLength,
            String name
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " не должен быть пустым");
        }

        String normalized = value.trim();

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " превышает " + maxLength + " символов"
            );
        }

        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " содержит управляющие символы"
            );
        }

        return normalized;
    }
}
