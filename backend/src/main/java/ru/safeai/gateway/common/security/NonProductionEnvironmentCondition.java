
package ru.safeai.gateway.common.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Enables development-only components outside production environments.
 *
 * <p>This condition is the exact inverse of
 * {@link ProductionEnvironmentCondition}.
 * Production detection must remain centralized.</p>
 */
public final class NonProductionEnvironmentCondition implements Condition {

    @Override
    public boolean matches(
            ConditionContext context,
            AnnotatedTypeMetadata metadata
    ) {
        return !new ProductionEnvironmentCondition()
                .matches(context, metadata);
    }
}
