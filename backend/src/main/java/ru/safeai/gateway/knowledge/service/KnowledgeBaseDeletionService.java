package ru.safeai.gateway.knowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseDeletionResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transactional acceptance of KB deletion; never performs external object-store I/O. */
@Service
public class KnowledgeBaseDeletionService {
    private final JdbcTemplate jdbc;
    private final AuditEventService audit;
    private final boolean baseAcceptEnabled;
    private final boolean baseExecutorEnabled;
    private final boolean documentAcceptEnabled;
    private final boolean documentExecutorEnabled;
    private final boolean erasureJournalEnabled;

    public KnowledgeBaseDeletionService(
            JdbcTemplate jdbc, AuditEventService audit,
            boolean baseAcceptEnabled, boolean baseExecutorEnabled,
            boolean documentAcceptEnabled, boolean documentExecutorEnabled) {
        this(jdbc, audit, baseAcceptEnabled, baseExecutorEnabled,
                documentAcceptEnabled, documentExecutorEnabled, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeBaseDeletionService(
            JdbcTemplate jdbc, AuditEventService audit,
            @Value("${safeai.knowledge.base-deletion.accept-enabled:false}") boolean baseAcceptEnabled,
            @Value("${safeai.knowledge.base-deletion.executor-enabled:false}") boolean baseExecutorEnabled,
            @Value("${safeai.knowledge.deletion.accept-enabled:false}") boolean documentAcceptEnabled,
            @Value("${safeai.knowledge.deletion.executor-enabled:false}") boolean documentExecutorEnabled,
            @Value("${safeai.knowledge.erasure-journal.enabled:false}") boolean erasureJournalEnabled) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.baseAcceptEnabled = baseAcceptEnabled;
        this.baseExecutorEnabled = baseExecutorEnabled;
        this.documentAcceptEnabled = documentAcceptEnabled;
        this.documentExecutorEnabled = documentExecutorEnabled;
        this.erasureJournalEnabled = erasureJournalEnabled;
    }

    public boolean enabled() {
        return baseAcceptEnabled && baseExecutorEnabled
                && documentAcceptEnabled && documentExecutorEnabled
                && erasureJournalEnabled;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public boolean capability(UUID baseId, SafeAiUserPrincipal actor) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.knowledge_bases
                              WHERE id=? AND organization_id=?)
                """, Boolean.class, baseId, actor.getOrganizationId()));
        if (!exists) throw new ResourceNotFoundException("База знаний не найдена.");
        return enabled();
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeBaseDeletionResponse accept(
            UUID baseId, long expectedVersion, String idempotencyKey,
            SafeAiUserPrincipal actor) {
        if (!enabled()) {
            throw new ConflictException("Безвозвратное удаление базы знаний не включено на сервере.");
        }
        if (expectedVersion < 0 || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9._:-]{12,128}")) {
            throw new BadRequestException("Неверные If-Match или Idempotency-Key.");
        }
        UUID orgId = actor.getOrganizationId();
        List<KnowledgeBaseDeletionResponse> prior = findByKey(orgId, idempotencyKey);
        if (!prior.isEmpty()) {
            return exactReplay(prior.getFirst(), baseId, expectedVersion);
        }

        // Existing security lock order is organization before Knowledge Base.
        List<Boolean> organizations = jdbc.query("""
                SELECT enabled FROM public.organizations WHERE id=? FOR SHARE
                """, (rs, n) -> rs.getBoolean(1), orgId);
        if (organizations.size() != 1 || !organizations.getFirst()) {
            throw new ConflictException("Организация недоступна.");
        }
        // Revalidate the persisted role under the same transaction after the
        // request-time principal was authenticated. Serialize with revocation.
        List<String> roles = jdbc.query("""
                SELECT r.name FROM public.users u
                JOIN public.user_roles ur ON ur.user_id=u.id
                JOIN public.roles r ON r.id=ur.role_id
                WHERE u.id=? AND u.organization_id=? AND u.enabled
                FOR SHARE OF u, ur
                """, (rs, n) -> rs.getString(1), actor.getId(), orgId);
        if (!roles.equals(List.of("ADMIN"))) {
            throw new ForbiddenOperationException("Права администратора были изменены.");
        }
        List<Base> bases = jdbc.query("""
                SELECT id, version, lifecycle_state FROM public.knowledge_bases
                 WHERE id=? AND organization_id=? FOR UPDATE
                """, (rs, n) -> new Base(rs.getObject("id", UUID.class),
                rs.getLong("version"), rs.getString("lifecycle_state")), baseId, orgId);
        if (bases.isEmpty()) throw new ResourceNotFoundException("База знаний не найдена.");
        Base base = bases.getFirst();
        if (!"AVAILABLE".equals(base.lifecycle())) {
            throw new ConflictException("Удаление этой базы уже выполняется.");
        }
        if (base.version() != expectedVersion) {
            throw new ConflictException("База изменена. Обновите данные и повторите действие.");
        }

        UUID operationId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO public.knowledge_base_deletions_v82
                    (id,organization_id,knowledge_base_id,requested_by_user_id,
                     expected_version,idempotency_key)
                VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, operationId, orgId, baseId, actor.getId(), expectedVersion, idempotencyKey);
        if (inserted != 1) {
            // INSERT .. ON CONFLICT waits for the concurrent winner. Under
            // READ COMMITTED the following statement sees its committed row.
            // Return the same operation for an exact replay; never create a
            // second operation or turn a successful retry into a spurious 409.
            List<KnowledgeBaseDeletionResponse> concurrent = findByKey(orgId, idempotencyKey);
            if (!concurrent.isEmpty()) {
                return exactReplay(concurrent.getFirst(), baseId, expectedVersion);
            }
            throw new ConflictException("Удаление этой базы уже зарегистрировано с другим ключом.");
        }
        if (jdbc.update("""
                UPDATE public.knowledge_bases
                   SET lifecycle_state='DELETING',enabled=false,version=version+1
                 WHERE id=? AND organization_id=? AND lifecycle_state='AVAILABLE' AND version=?
                """, baseId, orgId, expectedVersion) != 1) {
            throw new ConflictException("База изменилась до принятия удаления.");
        }

        // All children are enqueued within the SAME transaction as base disable.
        // Existing document deletions are reused and monitored, never duplicated.
        List<Child> children = jdbc.query("""
                SELECT id, lifecycle_state, version FROM public.knowledge_documents
                 WHERE organization_id=? AND knowledge_base_id=? ORDER BY id FOR UPDATE
                """, (rs, n) -> new Child(rs.getObject("id", UUID.class),
                rs.getString("lifecycle_state"), rs.getLong("version")), orgId, baseId);
        for (Child child : children) {
            if (!"AVAILABLE".equals(child.lifecycle())) {
                if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM public.knowledge_document_deletions_v78
                        WHERE organization_id=? AND knowledge_base_id=? AND document_id=?)
                        """, Boolean.class, orgId, baseId, child.id()))) {
                    throw new IllegalStateException("DELETING_DOCUMENT_WITHOUT_OPERATION");
                }
                continue;
            }
            UUID childId = UUID.randomUUID();
            String childKey = "base:" + operationId + ":" + child.id();
            if (jdbc.update("""
                    INSERT INTO public.knowledge_document_deletions_v78
                        (id,organization_id,knowledge_base_id,document_id,
                         requested_by_user_id,idempotency_key,expected_version)
                    VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, childId, orgId, baseId, child.id(), actor.getId(),
                    childKey, child.version()) != 1) {
                throw new ConflictException("Документ уже поставлен на удаление.");
            }
            if (jdbc.update("""
                    UPDATE public.knowledge_documents
                       SET enabled=false,lifecycle_state='DELETING',version=version+1
                     WHERE id=? AND knowledge_base_id=? AND organization_id=?
                       AND lifecycle_state='AVAILABLE' AND version=?
                    """, child.id(), baseId, orgId, child.version()) != 1) {
                throw new IllegalStateException("DOCUMENT_DELETION_ACCEPTANCE_RACE");
            }
        }
        // Audit intent must commit with both parent and children; no best-effort swallow.
        audit.recordPrincipalSnapshot(actor, orgId,
                AuditEventType.KNOWLEDGE_BASE_DELETION_REQUESTED,
                () -> Map.of("knowledgeBaseId", baseId.toString(),
                        "operationId", operationId.toString(),
                        "documentCount", children.size()));
        return byOperation(orgId, baseId, operationId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<KnowledgeBaseDeletionResponse> list(SafeAiUserPrincipal actor) {
        return jdbc.query("""
                SELECT * FROM public.knowledge_base_deletions_v82
                WHERE organization_id=? ORDER BY created_at DESC,id DESC LIMIT 50
                """, this::map, actor.getOrganizationId());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeBaseDeletionResponse current(UUID baseId, SafeAiUserPrincipal actor) {
        List<KnowledgeBaseDeletionResponse> result = jdbc.query("""
                SELECT * FROM public.knowledge_base_deletions_v82
                WHERE knowledge_base_id=? AND organization_id=?
                """, this::map, baseId, actor.getOrganizationId());
        if (result.isEmpty()) throw new ResourceNotFoundException("Операция удаления не найдена.");
        return result.getFirst();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeBaseDeletionResponse status(UUID baseId, UUID operationId,
                                                SafeAiUserPrincipal actor) {
        return byOperation(actor.getOrganizationId(), baseId, operationId);
    }

    private KnowledgeBaseDeletionResponse byOperation(UUID orgId, UUID baseId, UUID operationId) {
        List<KnowledgeBaseDeletionResponse> result = jdbc.query("""
                SELECT * FROM public.knowledge_base_deletions_v82
                WHERE organization_id=? AND knowledge_base_id=? AND id=?
                """, this::map, orgId, baseId, operationId);
        if (result.isEmpty()) throw new ResourceNotFoundException("Операция удаления не найдена.");
        return result.getFirst();
    }

    private List<KnowledgeBaseDeletionResponse> findByKey(UUID orgId, String key) {
        return jdbc.query("""
                SELECT * FROM public.knowledge_base_deletions_v82
                 WHERE organization_id=? AND idempotency_key=?
                """, this::map, orgId, key);
    }

    private KnowledgeBaseDeletionResponse exactReplay(
            KnowledgeBaseDeletionResponse previous, UUID baseId, long expectedVersion) {
        Long savedVersion = jdbc.queryForObject("""
                SELECT expected_version FROM public.knowledge_base_deletions_v82
                WHERE id=?
                """, Long.class, previous.operationId());
        if (!previous.knowledgeBaseId().equals(baseId)
                || savedVersion == null || savedVersion != expectedVersion) {
            throw new ConflictException("Idempotency-Key уже использован для другой операции.");
        }
        return previous;
    }

    private KnowledgeBaseDeletionResponse map(ResultSet rs, int row) throws SQLException {
        Timestamp done = rs.getTimestamp("completed_at");
        return new KnowledgeBaseDeletionResponse(
                rs.getObject("id", UUID.class), rs.getObject("knowledge_base_id", UUID.class),
                rs.getString("state"), rs.getTimestamp("created_at").toInstant(),
                done == null ? null : done.toInstant(), rs.getString("last_error_code"));
    }

    private record Base(UUID id, long version, String lifecycle) { }
    private record Child(UUID id, String lifecycle, long version) { }
}
