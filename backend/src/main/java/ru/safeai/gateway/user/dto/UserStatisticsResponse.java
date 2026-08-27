package ru.safeai.gateway.user.dto;

/**
 * User statistics for the caller's visible scope.
 *
 * <p>{@code administrators} counts accounts with system role {@code ADMIN};
 * {@code users} counts accounts with system role {@code USER}.</p>
 *
 * <p>For a tenant ADMIN the scope contains ordinary tenant accounts, so
 * {@code total} normally equals {@code administrators + users}.</p>
 *
 * <p>For SUPER_ADMIN the scope is global and {@code total} also includes
 * platform {@code SUPER_ADMIN} accounts. Therefore the public contract
 * intentionally does <strong>not</strong> guarantee
 * {@code total == administrators + users} for the global scope.</p>
 *
 * <p>{@code enabled + disabled == total} remains the scope invariant.</p>
 */
public record UserStatisticsResponse(
        long total,
        long administrators,
        long users,
        long enabled,
        long disabled
) {
}
