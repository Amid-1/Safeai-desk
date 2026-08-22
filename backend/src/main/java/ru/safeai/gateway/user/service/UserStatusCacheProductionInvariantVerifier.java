package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Production safety invariant for user security-state lookup.
 *
 * <p>PostgreSQL остаётся source of truth. Текущий Redis cache использует
 * best-effort AFTER_COMMIT invalidation, поэтому stale positive cache entry
 * теоретически может пережить security mutation, если Redis eviction
 * завершится ошибкой.</p>
 *
 * <p>До появления fail-safe invalidation strategy Redis user-status cache
 * запрещено использовать в production как источник security correctness.</p>
 */
@Component
@Profile({"prod", "production"})
@RequiredArgsConstructor
public class UserStatusCacheProductionInvariantVerifier
        implements ApplicationRunner {

    private final UserStatusCacheProperties properties;

    @Override
    public void run(
            @NonNull ApplicationArguments args
    ) {
        if (properties.isEnabled()) {
            throw new IllegalStateException(
                    "safeai.security.user-status-cache.enabled=true "
                            + "запрещён в профилях prod/production: "
                            + "текущая Redis invalidation является best-effort "
                            + "и не может быть boundary security correctness"
            );
        }
    }
}