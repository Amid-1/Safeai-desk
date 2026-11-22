// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/retention/RetentionManagementService.java
package ru.safeai.gateway.retention;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.service.ChatErasureWorker;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionExecutor;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionExecutor;
import ru.safeai.gateway.user.service.UserManagementProperties;

import java.util.List;
import java.util.Map;

/** Platform-wide admission policy; never executes or cancels deletion jobs. */
@Service
public class RetentionManagementService {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final PlatformProperties platform;
    private final AuditEventService audit;
    private final UserManagementProperties users;
    private final ObjectProvider<ChatErasureWorker> chatWorker;
    private final ObjectProvider<KnowledgeDocumentDeletionExecutor> documentWorker;
    private final ObjectProvider<KnowledgeBaseDeletionExecutor> baseWorker;

    public RetentionManagementService(JdbcTemplate jdbc, Environment environment,
            PlatformProperties platform, AuditEventService audit, UserManagementProperties users,
            ObjectProvider<ChatErasureWorker> chatWorker,
            ObjectProvider<KnowledgeDocumentDeletionExecutor> documentWorker,
            ObjectProvider<KnowledgeBaseDeletionExecutor> baseWorker) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.platform = platform;
        this.audit = audit;
        this.users = users;
        this.chatWorker = chatWorker;
        this.documentWorker = documentWorker;
        this.baseWorker = baseWorker;
    }

    record Policy(boolean chatAllowed, boolean knowledgeAllowed, long version) { }

    public record Gate(boolean configured, boolean workerInitialized) {
        boolean unavailable() {
            return !configured || !workerInitialized;
        }
    }

    public record Status(boolean chatAllowed, boolean knowledgeAllowed, long version,
                         Gate chat, Gate document, Gate base, long userRetentionSeconds) { }
    public record Change(Boolean chatAllowed, Boolean knowledgeAllowed, Long expectedVersion) { }

    @Transactional
    public Status status(SafeAiUserPrincipal actor) {
        requirePlatformAdmin(actor);
        return status(read(false));
    }

    @Transactional
    public Status update(Change change, SafeAiUserPrincipal actor) {
        requirePlatformAdmin(actor);
        if (change == null || change.chatAllowed() == null || change.knowledgeAllowed() == null
                || change.expectedVersion() == null || change.expectedVersion() < 0) {
            throw new BadRequestException("Не указаны параметры политики или её версия.");
        }
        Policy old = read(true);
        if (old.version() != change.expectedVersion()) {
            throw new ConflictException("Политика изменилась. Обновите страницу.");
        }
        Status current = status(old);
        // Base deletion delegates to the document executor; it cannot run independently.
        if ((!old.chatAllowed() && change.chatAllowed() && current.chat().unavailable())
                || (!old.knowledgeAllowed() && change.knowledgeAllowed()
                    && current.document().unavailable())) {
            throw new ConflictException("Сначала оператор должен подготовить инфраструктуру удаления.");
        }
        if (old.chatAllowed() == change.chatAllowed() && old.knowledgeAllowed() == change.knowledgeAllowed()) {
            return current;
        }
        int updated = jdbc.update("""
                UPDATE public.platform_erasure_policy_v102
                SET chat_allowed=?, knowledge_allowed=?, version=version+1 WHERE singleton AND version=?
                """, change.chatAllowed(), change.knowledgeAllowed(), old.version());
        if (updated != 1) {
            throw new ConflictException("Политика изменилась. Обновите страницу.");
        }
        audit.recordPrincipalSnapshot(actor, platform.organizationId(), AuditEventType.RETENTION_POLICY_CHANGED,
                () -> Map.of("previousChatAllowed", old.chatAllowed(), "chatAllowed", change.chatAllowed(),
                        "previousKnowledgeAllowed", old.knowledgeAllowed(),
                        "knowledgeAllowed", change.knowledgeAllowed(), "version", old.version() + 1));
        return status(read(false));
    }

    private Policy read(boolean lock) {
        return jdbc.queryForObject("""
                SELECT chat_allowed, knowledge_allowed, version FROM public.platform_erasure_policy_v102
                WHERE singleton
                """ + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Policy(rs.getBoolean(1), rs.getBoolean(2), rs.getLong(3)));
    }

    private Status status(Policy policy) {
        boolean chat = flag("safeai.chat.erasure.accept-enabled") && flag("safeai.chat.erasure.executor-enabled")
                && flag("safeai.chat.erasure.retention-approved");
        boolean document = flag("safeai.knowledge.deletion.accept-enabled")
                && flag("safeai.knowledge.deletion.executor-enabled")
                && flag("safeai.knowledge.erasure-journal.enabled")
                && flag("safeai.knowledge.erasure-journal.object-lock-required");
        boolean base = document && flag("safeai.knowledge.base-deletion.accept-enabled")
                && flag("safeai.knowledge.base-deletion.executor-enabled");
        boolean documentWorkerInitialized = documentWorker.getIfAvailable() != null;
        boolean baseWorkerInitialized = baseWorker.getIfAvailable() != null;
        return new Status(policy.chatAllowed(), policy.knowledgeAllowed(), policy.version(),
                new Gate(chat, chatWorker.getIfAvailable() != null),
                new Gate(document, documentWorkerInitialized),
                new Gate(base, documentWorkerInitialized && baseWorkerInitialized),
                users.permanentDeletionRetention().toSeconds());
    }

    private boolean flag(String key) {
        return environment.getProperty(key, Boolean.class, false);
    }

    private void requirePlatformAdmin(SafeAiUserPrincipal actor) {
        if (actor == null || !platform.organizationId().equals(actor.getOrganizationId())) {
            throw new ForbiddenOperationException("Недостаточно прав.");
        }
        List<String> roles = jdbc.query("""
                SELECT r.name FROM public.organizations o JOIN public.users u ON u.organization_id=o.id
                JOIN public.user_roles ur ON ur.user_id=u.id JOIN public.roles r ON r.id=ur.role_id
                WHERE o.id=? AND o.enabled AND u.id=? AND u.enabled FOR SHARE OF o,u,ur
                """, (rs, row) -> rs.getString(1), platform.organizationId(), actor.getId());
        if (!roles.equals(List.of("SUPER_ADMIN"))) {
            throw new ForbiddenOperationException("Недостаточно прав.");
        }
    }
}
