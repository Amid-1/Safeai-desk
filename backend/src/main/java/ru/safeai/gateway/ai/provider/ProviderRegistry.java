package ru.safeai.gateway.ai.provider;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime dispatch registry for immutable physical provider targets.
 *
 * <p>The registry never dispatches by provider name alone. Configuration,
 * configuration version, deployment, deployment version and requested physical
 * model are all part of the key so a routed operation cannot silently execute
 * against a different physical target.</p>
 *
 * <p>{@code allowedResolvedModels} is deliberately not part of the adapter key:
 * it is route/request validation evidence, while the registry identifies the
 * physical adapter endpoint itself. The original
 * {@link ProviderExecutionTarget} must still be used later to validate the
 * provider response.</p>
 */
@Component
@SuppressWarnings("unused")
public final class ProviderRegistry {

    private final Map<TargetKey, AiProvider> providers;

    public ProviderRegistry(
            Collection<AiProvider> providerBeans
    ) {
        Objects.requireNonNull(
                providerBeans,
                "providerBeans не должен быть null"
        );

        Map<TargetKey, AiProvider> indexed =
                new LinkedHashMap<>();

        for (AiProvider provider : providerBeans) {
            if (provider == null) {
                throw new IllegalStateException(
                        "providerBeans не должен содержать null"
                );
            }

            ProviderExecutionTarget target =
                    Objects.requireNonNull(
                            provider.executionTarget(),
                            "provider.executionTarget() не должен быть null"
                    );

            TargetKey key =
                    TargetKey.from(target);

            AiProvider previous =
                    indexed.putIfAbsent(
                            key,
                            provider
                    );

            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate physical provider target: "
                                + key
                                + "; adapters="
                                + previous.getClass().getName()
                                + ", "
                                + provider.getClass().getName()
                );
            }
        }

        providers =
                Map.copyOf(indexed);
    }

    /**
     * Resolves the exact adapter selected by immutable route/execution evidence.
     *
     * <p>Fails closed before provider I/O if the selected target is not active
     * in this application instance.</p>
     */
    public AiProvider require(
            ProviderExecutionTarget target
    ) {
        Objects.requireNonNull(
                target,
                "target не должен быть null"
        );

        TargetKey key =
                TargetKey.from(target);

        AiProvider provider =
                providers.get(key);

        if (provider == null) {
            throw new IllegalStateException(
                    "Physical provider target is not registered: "
                            + key
            );
        }

        return provider;
    }

    /**
     * Startup/test diagnostic that does not expose credentials or secrets.
     */
    public int registeredTargetCount() {
        return providers.size();
    }

    private record TargetKey(
            String providerType,
            String providerConfigurationRef,
            String providerConfigurationVersion,
            String deploymentRef,
            String deploymentVersion,
            String requestedPhysicalModel
    ) {
        private TargetKey {
            Objects.requireNonNull(
                    providerType,
                    "providerType"
            );
            Objects.requireNonNull(
                    providerConfigurationRef,
                    "providerConfigurationRef"
            );
            Objects.requireNonNull(
                    providerConfigurationVersion,
                    "providerConfigurationVersion"
            );
            Objects.requireNonNull(
                    deploymentRef,
                    "deploymentRef"
            );
            Objects.requireNonNull(
                    deploymentVersion,
                    "deploymentVersion"
            );
            Objects.requireNonNull(
                    requestedPhysicalModel,
                    "requestedPhysicalModel"
            );
        }

        private static TargetKey from(
                ProviderExecutionTarget target
        ) {
            Objects.requireNonNull(
                    target,
                    "target не должен быть null"
            );

            return new TargetKey(
                    target.providerType(),
                    target.providerConfigurationRef(),
                    target.providerConfigurationVersion(),
                    target.deploymentRef(),
                    target.deploymentVersion(),
                    target.requestedPhysicalModel()
            );
        }
    }
}
