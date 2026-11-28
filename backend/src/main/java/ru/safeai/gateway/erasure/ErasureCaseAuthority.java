// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseAuthority.java
package ru.safeai.gateway.erasure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Target;

import java.util.List;
import java.util.UUID;

/** Database-authoritative tenant, role, owner and target checks. */
@Component
public class ErasureCaseAuthority {
    private final JdbcTemplate jdbc;
    private final PlatformProperties platform;

    public ErasureCaseAuthority(JdbcTemplate jdbc, PlatformProperties platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    public long targetVersion(Target target, SafeAiUserPrincipal actor) {
        ErasureCaseRules.validate(target);
        List<String> roles = liveRoles(actor);
        UUID org = actor.getOrganizationId();
        UUID user = actor.getId();
        boolean admin = roles.contains("ADMIN");
        if (target.resourceType() == ErasureCaseTypes.ResourceType.USER_ACCOUNT
                && roles.contains("SUPER_ADMIN")) throw denied();
        if (target.resourceType() == ErasureCaseTypes.ResourceType.USER_ACCOUNT
                && !user.equals(target.resourceId())) throw notFound();
        List<Long> versions;
        switch (target.resourceType()) {
            case USER_ACCOUNT -> versions = jdbc.query("""
                    SELECT version FROM public.users WHERE id=? AND organization_id=?
                    FOR SHARE
                    """, (rs, row) -> rs.getLong(1), target.resourceId(), org);
            case CHAT -> versions = jdbc.query("""
                    SELECT version FROM public.chat_sessions WHERE id=? AND organization_id=?
                    AND user_id=? FOR SHARE
                    """, (rs, row) -> rs.getLong(1), target.resourceId(), org, user);
            case KNOWLEDGE_DOCUMENT -> versions = jdbc.query("""
                    SELECT d.version FROM public.knowledge_documents d
                    JOIN public.knowledge_bases b ON b.id=d.knowledge_base_id
                    AND b.organization_id=d.organization_id
                    WHERE d.id=? AND d.organization_id=? AND (
                      ? OR d.created_by_user_id=? OR EXISTS (
                        SELECT 1 FROM public.knowledge_base_memberships m
                        WHERE m.knowledge_base_id=b.id AND m.organization_id=?
                          AND m.user_id=? AND m.access_level='OWNER'))
                    FOR SHARE OF d,b
                    """, (rs, row) -> rs.getLong(1), target.resourceId(), org,
                    admin, user, org, user);
            case KNOWLEDGE_BASE -> versions = jdbc.query("""
                    SELECT b.version FROM public.knowledge_bases b
                    WHERE b.id=? AND b.organization_id=? AND (
                      ? OR EXISTS (SELECT 1 FROM public.knowledge_base_memberships m
                        WHERE m.knowledge_base_id=b.id AND m.organization_id=?
                          AND m.user_id=? AND m.access_level='OWNER'))
                    FOR SHARE OF b
                    """, (rs, row) -> rs.getLong(1), target.resourceId(), org,
                    admin, org, user);
            case ORGANIZATION -> {
                if (!admin || platform.organizationId().equals(org)) throw denied();
                versions = jdbc.query("""
                        SELECT version FROM public.organizations WHERE id=? AND enabled
                        FOR SHARE
                        """, (rs, row) -> rs.getLong(1), target.resourceId());
                if (!org.equals(target.resourceId())) throw notFound();
            }
            default -> throw notFound();
        }
        if (versions.size() != 1) throw notFound();
        return versions.getFirst();
    }

    public void requireActor(SafeAiUserPrincipal actor) {
        liveRoles(actor);
    }

    public boolean tenantAdmin(SafeAiUserPrincipal actor) {
        return liveRoles(actor).contains("ADMIN");
    }

    public boolean platformSuperAdmin(SafeAiUserPrincipal actor) {
        return actor != null && platform.organizationId().equals(actor.getOrganizationId())
                && liveRoles(actor).contains("SUPER_ADMIN");
    }

    List<String> liveRoles(SafeAiUserPrincipal actor) {
        if (actor == null) throw denied();
        List<String> roles = jdbc.query("""
                SELECT r.name FROM public.users u
                JOIN public.organizations o ON o.id=u.organization_id
                JOIN public.user_roles ur ON ur.user_id=u.id
                JOIN public.roles r ON r.id=ur.role_id
                WHERE u.id=? AND u.organization_id=? AND u.enabled AND o.enabled
                AND u.token_version=? AND o.auth_version=?
                FOR SHARE OF u,o,ur
                """, (rs, row) -> rs.getString(1), actor.getId(),
                actor.getOrganizationId(), actor.getTokenVersion(),
                actor.getOrganizationAuthVersion());
        if (roles.isEmpty()) throw denied();
        return roles;
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Объект не найден.");
    }

    private ForbiddenOperationException denied() {
        return new ForbiddenOperationException("Недостаточно прав.");
    }
}
