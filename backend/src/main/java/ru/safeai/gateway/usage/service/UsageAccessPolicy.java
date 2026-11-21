// backend/src/main/java/ru/safeai/gateway/usage/service/UsageAccessPolicy.java

package ru.safeai.gateway.usage.service;

import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;

import java.util.Objects;

/** Defense in depth for direct calls, in addition to Spring method security. */
final class UsageAccessPolicy {
    private UsageAccessPolicy() { }

    static void requireReader(SafeAiUserPrincipal user) {
        Objects.requireNonNull(user, "currentUser");
        if (!isSuperAdmin(user) && !user.authorityNames().contains(SystemRole.ADMIN.authority())) {
            throw new ForbiddenOperationException("Usage доступен только администраторам");
        }
    }

    static boolean isSuperAdmin(SafeAiUserPrincipal user) {
        return user.authorityNames().contains(SystemRole.SUPER_ADMIN.authority());
    }
}
