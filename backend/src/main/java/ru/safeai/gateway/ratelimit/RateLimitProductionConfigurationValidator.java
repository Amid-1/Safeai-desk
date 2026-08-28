package ru.safeai.gateway.ratelimit;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Production fail-fast invariants for security/cost rate limits.
 *
 * <p>Login and refresh limiters are mandatory in production. Paid AI traffic
 * is also limited by default; disabling its limiter requires an explicit
 * production escape hatch so a typo or generic enabled=false cannot silently
 * create unlimited provider traffic.</p>
 */
@Component
@EnableConfigurationProperties(
        RateLimitProductionProperties.class
)
public final class RateLimitProductionConfigurationValidator {

    private final LoginRateLimitProperties loginProperties;
    private final RefreshRateLimitProperties refreshProperties;
    private final AiMessageRateLimitProperties aiMessageProperties;
    private final RateLimitProductionProperties productionProperties;
    private final Environment environment;

    public RateLimitProductionConfigurationValidator(
            LoginRateLimitProperties loginProperties,
            RefreshRateLimitProperties refreshProperties,
            AiMessageRateLimitProperties aiMessageProperties,
            RateLimitProductionProperties productionProperties,
            Environment environment
    ) {
        this.loginProperties =
                Objects.requireNonNull(
                        loginProperties,
                        "loginProperties не должен быть null"
                );

        this.refreshProperties =
                Objects.requireNonNull(
                        refreshProperties,
                        "refreshProperties не должен быть null"
                );

        this.aiMessageProperties =
                Objects.requireNonNull(
                        aiMessageProperties,
                        "aiMessageProperties не должен быть null"
                );

        this.productionProperties =
                Objects.requireNonNull(
                        productionProperties,
                        "productionProperties не должен быть null"
                );

        this.environment =
                Objects.requireNonNull(
                        environment,
                        "environment не должен быть null"
                );
    }

    @PostConstruct
    void validate() {
        if (!environment.acceptsProfiles(
                Profiles.of(
                        "prod",
                        "production"
                )
        )) {
            return;
        }

        if (!loginProperties.isEnabled()) {
            throw new IllegalStateException(
                    "В production safeai.rate-limit.login.enabled "
                            + "не может быть false"
            );
        }

        if (!refreshProperties.isEnabled()) {
            throw new IllegalStateException(
                    "В production safeai.rate-limit.refresh.enabled "
                            + "не может быть false"
            );
        }

        if (!aiMessageProperties.isEnabled()
                && !productionProperties
                .isUnlimitedAiTrafficAllowed()) {

            throw new IllegalStateException(
                    "В production отключение "
                            + "safeai.rate-limit.ai-messages.enabled "
                            + "требует явного "
                            + "safeai.rate-limit.production."
                            + "allow-unlimited-ai-traffic=true"
            );
        }
    }
}
