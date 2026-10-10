package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.ProductionEnvironmentCondition;

/** Fail closed when the best-effort Redis cache is enabled in production. */
@Component
@Conditional(ProductionEnvironmentCondition.class)
@RequiredArgsConstructor
public class UserStatusCacheProductionInvariantVerifier implements ApplicationRunner {
    private final UserStatusCacheProperties properties;

    @Override
    public void run(@NonNull ApplicationArguments args) {
        if (properties.isEnabled()) {
            throw new IllegalStateException(
                    "safeai.security.user-status-cache.enabled=true запрещён в production: "
                            + "best-effort cache invalidation не обеспечивает немедленный security fence");
        }
    }
}
