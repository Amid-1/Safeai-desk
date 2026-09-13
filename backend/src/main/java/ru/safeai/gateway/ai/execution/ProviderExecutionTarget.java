package ru.safeai.gateway.ai.execution;

import java.util.Objects;
import java.util.Set;

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
    public ProviderExecutionTarget {
        providerType = required(providerType, "providerType");
        providerConfigurationRef = required(providerConfigurationRef, "providerConfigurationRef");
        providerConfigurationVersion = required(providerConfigurationVersion, "providerConfigurationVersion");
        deploymentRef = required(deploymentRef, "deploymentRef");
        deploymentVersion = required(deploymentVersion, "deploymentVersion");
        requestedPhysicalModel = required(requestedPhysicalModel, "requestedPhysicalModel");
        allowedResolvedModels = allowedResolvedModels == null
                ? Set.of(requestedPhysicalModel)
                : Set.copyOf(allowedResolvedModels);
        if (allowedResolvedModels.isEmpty() || allowedResolvedModels.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("allowedResolvedModels не должен быть пустым");
        }
    }

    public static ProviderExecutionTarget staticTarget(String provider, String model) {
        String normalizedProvider = required(provider, "provider");
        String normalizedModel = required(model, "model");
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
        if (!allowedResolvedModels.contains(required(actualModel, "actualModel"))) {
            throw new ResolvedModelMismatchException(requestedPhysicalModel, actualModel);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " не должен быть пустым");
        }
        return value.trim();
    }
}
