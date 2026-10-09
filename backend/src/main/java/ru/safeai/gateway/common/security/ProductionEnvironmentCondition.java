
package ru.safeai.gateway.common.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Enables production-only security invariants without depending on
 * exactly one Spring profile spelling.
 *
 * <p>The preferred signal is {@code safeai.environment=production}.
 * For rolling/backward compatibility, canonical and qualified
 * production profiles such as {@code prod}, {@code production},
 * {@code prod-k8s} and {@code production-eu} are also recognized.</p>
 */
public final class ProductionEnvironmentCondition implements Condition {

    private static final String ENVIRONMENT_PROPERTY = "safeai.environment";

    private static final Pattern PRODUCTION_NAME = Pattern.compile(
            "^(?:prod|production)(?:[-_.].+)?$",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean matches(
            ConditionContext context,
            AnnotatedTypeMetadata metadata
    ) {
        Objects.requireNonNull(
                context,
                "context не должен быть null"
        );

        Environment environment = Objects.requireNonNull(
                context.getEnvironment(),
                "context.environment не должен быть null"
        );

        String explicitEnvironment = environment.getProperty(
                ENVIRONMENT_PROPERTY
        );

        if (explicitEnvironment != null
                && isProductionName(explicitEnvironment)) {
            return true;
        }

        String[] activeProfiles = Objects.requireNonNull(
                environment.getActiveProfiles(),
                "environment.activeProfiles не должен быть null"
        );

        return Arrays.stream(activeProfiles)
                .anyMatch(ProductionEnvironmentCondition::isProductionName);
    }

    static boolean isProductionName(String value) {
        return PRODUCTION_NAME.matcher(value.trim()).matches();
    }
}
