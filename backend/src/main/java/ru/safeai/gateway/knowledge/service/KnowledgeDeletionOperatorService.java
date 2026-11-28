package ru.safeai.gateway.knowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Safe, explicitly authorized continuation of NEEDS_REVIEW operations only.
 * BLOCKED_BY_RETENTION is immutable under V81 and cannot be retried here.
 * The operator never deletes S3 or SQL content; only the original fenced
 * V78/V82 executors can do so after an independently verified inventory.
 */
@Service
public class KnowledgeDeletionOperatorService {
    private final JdbcTemplate jdbc;
    private final AuditEventService audit;
    private final boolean resumeEnabled;

    public KnowledgeDeletionOperatorService(
            JdbcTemplate jdbc,
            AuditEventService audit,
            @Value("${safeai.knowledge.deletion.recovery-enabled:false}") boolean recoveryEnabled,
            @Value("${safeai.knowledge.deletion.accept-enabled:false}") boolean documentAccept,
            @Value("${safeai.knowledge.deletion.executor-enabled:false}") boolean documentExecutor,
            @Value("${safeai.knowledge.base-deletion.accept-enabled:false}") boolean baseAccept,
            @Value("${safeai.knowledge.base-deletion.executor-enabled:false}") boolean baseExecutor) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.resumeEnabled = recoveryEnabled && documentAccept && documentExecutor
                && baseAccept && baseExecutor;
    }

    public record Diagnostics(UUID operationId, UUID knowledgeBaseId, String kind,
                              String state, String lastErrorCode, Instant updatedAt,
                              int attempts, int inventoryCount, int unsettledUploadIntents,
                              int blockedChildren, int reviewChildren,
                              boolean recoveryConfigured) { }

    private record Operation(UUID id, UUID baseId, UUID documentId, String state,
                             String error, Instant updatedAt, int attempts,
                             UUID leaseToken, Instant leaseUntil) { }

    @Transactional(readOnly = true)
    public Diagnostics documentDiagnostics(UUID baseId, UUID operationId, SafeAiUserPrincipal actor) {
        requireCurrentAdmin(actor, false);
        Operation op = documentOperation(actor.getOrganizationId(), baseId, operationId, false);
        return documentDetails(op, actor.getOrganizationId());
    }

    @Transactional(readOnly = true)
    public Diagnostics baseDiagnostics(UUID baseId, UUID operationId, SafeAiUserPrincipal actor) {
        requireCurrentAdmin(actor, false);
        Operation op = baseOperation(actor.getOrganizationId(), baseId, operationId, false);
        return baseDetails(op, actor.getOrganizationId());
    }

    @Transactional
    public Diagnostics resumeDocument(UUID baseId, UUID operationId, String expectedUpdatedAt,
                                      String reasonCode, SafeAiUserPrincipal actor) {
        requireEnabled();
        String reason = validateReason(reasonCode);
        Instant expected = parseUpdatedAt(expectedUpdatedAt);
        requireCurrentAdmin(actor, true);
        UUID orgId = actor.getOrganizationId();
        // Match executor lock ordering: document first, operation second.
        List<String> documents = jdbc.queryForList("""
                SELECT d.lifecycle_state FROM public.knowledge_documents d
                JOIN public.knowledge_document_deletions_v78 op
                  ON op.document_id=d.id AND op.knowledge_base_id=d.knowledge_base_id
                 AND op.organization_id=d.organization_id
                WHERE op.organization_id=? AND op.knowledge_base_id=? AND op.id=?
                  AND NOT d.enabled FOR UPDATE OF d
                """, String.class, orgId, baseId, operationId);
        if (documents.size() != 1 || !"DELETING".equals(documents.getFirst())) {
            throw new ConflictException("Документ не находится в защищённом состоянии DELETING.");
        }
        Operation op = documentOperation(orgId, baseId, operationId, true);
        requireReviewAndFresh(op, expected);
        int pending = unsettledDocument(orgId, baseId, op.documentId());
        if (pending != 0) {
            throw new ConflictException("Обнаружены неопределённые загрузки: необходим разбор upload intents.");
        }
        int updated = jdbc.update("""
                UPDATE public.knowledge_document_deletions_v78
                   SET state='DELETING', next_attempt_at=clock_timestamp(),
                       lease_token=NULL, lease_until=NULL, updated_at=clock_timestamp()
                 WHERE id=? AND organization_id=? AND knowledge_base_id=?
                   AND state='NEEDS_REVIEW' AND updated_at=?
                   AND lease_token IS NULL AND lease_until IS NULL
                """, op.id(), orgId, baseId, Timestamp.from(expected));
        if (updated != 1) throw new ConflictException("Операция изменилась. Обновите диагностику.");
        audit.recordPrincipalSnapshot(actor, orgId,
                AuditEventType.KNOWLEDGE_DOCUMENT_DELETION_RESUMED,
                () -> Map.of("operationId", op.id().toString(),
                        "knowledgeBaseId", baseId.toString(),
                        "reasonCode", reason,
                        "previousErrorCode", op.error() == null ? "NONE" : op.error()));
        return documentDetails(documentOperation(orgId, baseId, operationId, false), orgId);
    }

    @Transactional
    public Diagnostics resumeBase(UUID baseId, UUID operationId, String expectedUpdatedAt,
                                  String reasonCode, SafeAiUserPrincipal actor) {
        requireEnabled();
        String reason = validateReason(reasonCode);
        Instant expected = parseUpdatedAt(expectedUpdatedAt);
        requireCurrentAdmin(actor, true);
        UUID orgId = actor.getOrganizationId();
        List<String> bases = jdbc.queryForList("""
                SELECT lifecycle_state FROM public.knowledge_bases
                WHERE organization_id=? AND id=? AND NOT enabled FOR UPDATE
                """, String.class, orgId, baseId);
        if (bases.size() != 1 || !"DELETING".equals(bases.getFirst())) {
            throw new ConflictException("База не находится в защищённом состоянии DELETING.");
        }
        Operation op = baseOperation(orgId, baseId, operationId, true);
        requireReviewAndFresh(op, expected);
        int blocked = count("""
                SELECT count(*) FROM public.knowledge_document_deletions_v78
                WHERE organization_id=? AND knowledge_base_id=?
                  AND state IN ('NEEDS_REVIEW','BLOCKED_BY_RETENTION')
                """, orgId, baseId);
        if (blocked > 0) {
            throw new ConflictException("Сначала разберите дочерние операции удаления.");
        }
        int pending = count("""
                SELECT count(*) FROM public.knowledge_storage_upload_intents
                WHERE organization_id=? AND knowledge_base_id=?
                  AND state NOT IN ('LINKED','CLEANED')
                """, orgId, baseId);
        if (pending > 0) {
            throw new ConflictException("Неопределённые загрузки запрещают продолжение удаления базы.");
        }
        int updated = jdbc.update("""
                UPDATE public.knowledge_base_deletions_v82
                   SET state='DELETING', next_attempt_at=clock_timestamp(),
                       lease_token=NULL, lease_until=NULL, updated_at=clock_timestamp()
                 WHERE id=? AND organization_id=? AND knowledge_base_id=?
                   AND state='NEEDS_REVIEW' AND updated_at=?
                   AND lease_token IS NULL AND lease_until IS NULL
                """, op.id(), orgId, baseId, Timestamp.from(expected));
        if (updated != 1) throw new ConflictException("Операция изменилась. Обновите диагностику.");
        audit.recordPrincipalSnapshot(actor, orgId,
                AuditEventType.KNOWLEDGE_BASE_DELETION_RESUMED,
                () -> Map.of("operationId", op.id().toString(),
                        "knowledgeBaseId", baseId.toString(),
                        "reasonCode", reason,
                        "previousErrorCode", op.error() == null ? "NONE" : op.error()));
        return baseDetails(baseOperation(orgId, baseId, operationId, false), orgId);
    }

    private void requireEnabled() {
        if (!resumeEnabled) {
            throw new ConflictException("Операторское продолжение удаления выключено на сервере.");
        }
    }

    private void requireCurrentAdmin(SafeAiUserPrincipal actor, boolean lockRows) {
        if (!KnowledgeAccessService.isAdmin(actor)) {
            throw new ForbiddenOperationException("Для оператора обязательна роль ADMIN.");
        }
        UUID orgId = actor.getOrganizationId();
        String orgLock = lockRows ? " FOR SHARE" : "";
        List<Boolean> orgs = jdbc.query("SELECT enabled FROM public.organizations WHERE id=?" + orgLock,
                (rs, row) -> rs.getBoolean(1), orgId);
        if (orgs.size() != 1 || !orgs.getFirst()) {
            throw new ForbiddenOperationException("Организация отключена.");
        }
        String userLock = lockRows ? " FOR SHARE OF u, ur" : "";
        List<String> roles = jdbc.query("""
                SELECT r.name FROM public.users u
                JOIN public.user_roles ur ON ur.user_id=u.id
                JOIN public.roles r ON r.id=ur.role_id
                WHERE u.id=? AND u.organization_id=? AND u.enabled
                """ + userLock, (rs, row) -> rs.getString(1), actor.getId(), orgId);
        if (!roles.contains("ADMIN")) {
            throw new ForbiddenOperationException("Действующая роль ADMIN не подтверждена.");
        }
    }

    private void requireReviewAndFresh(Operation op, Instant expected) {
        if (!"NEEDS_REVIEW".equals(op.state())) {
            throw new ConflictException("Разрешено продолжать только NEEDS_REVIEW; BLOCKED_BY_RETENTION неизменяем.");
        }
        if (!expected.equals(op.updatedAt())) {
            throw new ConflictException("Операция изменилась. Обновите диагностику.");
        }
        if (op.leaseToken() != null || op.leaseUntil() != null) {
            throw new ConflictException("У операции ещё есть lease; продолжение запрещено.");
        }
    }

    private Operation documentOperation(UUID orgId, UUID baseId, UUID id, boolean lock) {
        List<Operation> found = jdbc.query("""
                SELECT id,knowledge_base_id,document_id,state,last_error_code,updated_at,
                       attempts,lease_token,lease_until
                FROM public.knowledge_document_deletions_v78
                WHERE organization_id=? AND knowledge_base_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : ""), this::map, orgId, baseId, id);
        if (found.isEmpty()) throw new ResourceNotFoundException("Операция удаления документа не найдена.");
        return found.getFirst();
    }

    private Operation baseOperation(UUID orgId, UUID baseId, UUID id, boolean lock) {
        List<Operation> found = jdbc.query("""
                SELECT id,knowledge_base_id,NULL::uuid AS document_id,state,last_error_code,
                       updated_at,attempts,lease_token,lease_until
                FROM public.knowledge_base_deletions_v82
                WHERE organization_id=? AND knowledge_base_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : ""), this::map, orgId, baseId, id);
        if (found.isEmpty()) throw new ResourceNotFoundException("Операция удаления базы не найдена.");
        return found.getFirst();
    }

    private Operation map(ResultSet rs, int row) throws SQLException {
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        return new Operation(rs.getObject("id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("state"),
                rs.getString("last_error_code"), rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("attempts"), rs.getObject("lease_token", UUID.class),
                leaseUntil == null ? null : leaseUntil.toInstant());
    }

    private Diagnostics documentDetails(Operation op, UUID orgId) {
        int inventory = count("""
                SELECT count(*) FROM public.knowledge_document_erasure_objects_v81
                WHERE operation_id=?
                """, op.id());
        return new Diagnostics(op.id(), op.baseId(), "DOCUMENT", op.state(), op.error(),
                op.updatedAt(), op.attempts(), inventory,
                unsettledDocument(orgId, op.baseId(), op.documentId()), 0, 0, resumeEnabled);
    }

    private Diagnostics baseDetails(Operation op, UUID orgId) {
        int inventory = count("""
                SELECT count(*) FROM public.knowledge_document_erasure_objects_v81 i
                JOIN public.knowledge_document_deletions_v78 d ON d.id=i.operation_id
                WHERE d.organization_id=? AND d.knowledge_base_id=?
                """, orgId, op.baseId());
        int pending = count("""
                SELECT count(*) FROM public.knowledge_storage_upload_intents
                WHERE organization_id=? AND knowledge_base_id=?
                  AND state NOT IN ('LINKED','CLEANED')
                """, orgId, op.baseId());
        int blocked = count("""
                SELECT count(*) FROM public.knowledge_document_deletions_v78
                WHERE organization_id=? AND knowledge_base_id=? AND state='BLOCKED_BY_RETENTION'
                """, orgId, op.baseId());
        int review = count("""
                SELECT count(*) FROM public.knowledge_document_deletions_v78
                WHERE organization_id=? AND knowledge_base_id=? AND state='NEEDS_REVIEW'
                """, orgId, op.baseId());
        return new Diagnostics(op.id(), op.baseId(), "BASE", op.state(), op.error(),
                op.updatedAt(), op.attempts(), inventory, pending, blocked, review, resumeEnabled);
    }

    private int unsettledDocument(UUID orgId, UUID baseId, UUID documentId) {
        return count("""
                SELECT count(*) FROM public.knowledge_storage_upload_intents
                WHERE organization_id=? AND knowledge_base_id=? AND document_id=?
                  AND state NOT IN ('LINKED','CLEANED')
                """, orgId, baseId, documentId);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private static Instant parseUpdatedAt(String value) {
        try {
            if (value == null) throw new DateTimeParseException("missing", "", 0);
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new BadRequestException("expectedUpdatedAt должен быть ISO-8601 из диагностики.");
        }
    }

    private static String validateReason(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{4,63}")) {
            throw new BadRequestException("Требуется код причины оператора длиной 5–64 символа.");
        }
        return value;
    }
}
