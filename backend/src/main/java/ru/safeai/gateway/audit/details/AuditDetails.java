package ru.safeai.gateway.audit.details;

import java.util.Map;

/**
 * Typed audit payload contract.
 *
 * <p>Implementations must expose metadata/evidence only. Request bodies,
 * prompts, response bodies, credentials, cookies and authentication material
 * must never be returned from {@link #toMap()}.</p>
 *
 * <p>The central sanitizer still provides defense-in-depth; a typed payload
 * is preferred to an unrestricted map whose safety cannot be established
 * using only a key denylist.</p>
 */
@FunctionalInterface
public interface AuditDetails {

    Map<String, Object> toMap();
}
