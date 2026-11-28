package ru.safeai.gateway.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.*;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.entity.ChatErasureState;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.chat.repository.ChatTurnMutexRepository;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.retention.ErasureAdmissionPolicy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Chat lifecycle: archive/restore and fail-closed admission of content erasure.
 * The latter retains all immutable accounting/evidence rows and is NOT a
 * physical erasure of a PostgreSQL session. No provider calls happen here.
 */
@Service
public class ChatLifecycleService {
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final ChatTurnMutexRepository mutex;
    private final ChatMapper mapper;
    private final ChatProperties chatProperties;
    private final AuditEventService audit;
    private final JdbcTemplate jdbc;
    private final ChatErasurePreconditions preconditions;
    private final Clock clock;
    private final boolean acceptEnabled;
    private final boolean executorEnabled;
    private final boolean retentionApproved;

    public ChatLifecycleService(
            ChatSessionRepository sessions, ChatMessageRepository messages,
            ChatTurnMutexRepository mutex, ChatMapper mapper,
            ChatProperties chatProperties, AuditEventService audit,
            JdbcTemplate jdbc, Clock clock,
            @Value("${safeai.chat.erasure.accept-enabled:false}") boolean acceptEnabled,
            @Value("${safeai.chat.erasure.executor-enabled:false}") boolean executorEnabled,
            @Value("${safeai.chat.erasure.retention-approved:false}") boolean retentionApproved) {
        this.sessions = Objects.requireNonNull(sessions);
        this.messages = Objects.requireNonNull(messages);
        this.mutex = Objects.requireNonNull(mutex);
        this.mapper = Objects.requireNonNull(mapper);
        this.chatProperties = Objects.requireNonNull(chatProperties);
        this.audit = Objects.requireNonNull(audit);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.preconditions = new ChatErasurePreconditions(this.jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.acceptEnabled = acceptEnabled;
        this.executorEnabled = executorEnabled;
        this.retentionApproved = retentionApproved;
    }

    @Transactional
    public Slice<ChatResponse> archived(SafeAiUserPrincipal actor, Pageable pageable) {
        requireTenantUser(actor);
        requirePersistedMembership(actor);
        return sessions.findByUser_IdAndOrganization_IdAndArchivedAtIsNotNullAndErasureState(
                actor.getId(), actor.getOrganizationId(), ChatErasureState.AVAILABLE, pageable)
                .map(mapper::toChatResponse);
    }

    @Transactional
    public ChatDetailsResponse archivedDetails(UUID chatId, SafeAiUserPrincipal actor) {
        requireTenantUser(actor);
        requirePersistedMembership(actor);
        ChatSessionEntity chat = sessions
                .findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNotNullAndErasureState(
                        chatId, actor.getId(), actor.getOrganizationId(), ChatErasureState.AVAILABLE)
                .orElseThrow(ChatLifecycleService::notFound);
        var items = messages.findVisibleBySessionId(chatId,
                        PageRequest.of(0, chatProperties.detailsMessageLimit(),
                                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .getContent().reversed().stream().map(mapper::toMessageResponse).toList();
        return mapper.toChatDetailsResponse(chat, items);
    }

    @Transactional
    public void archive(UUID chatId, SafeAiUserPrincipal actor) {
        requireTenantUser(actor);
        requirePersistedMembership(actor);
        mutex.lockChat(chatId);
        ChatSessionEntity chat = ownedAvailableForUpdate(chatId, actor);
        if (chat.getArchivedAt() != null) return; // idempotent retry
        rejectInFlightTurn(chatId);
        Instant archivedAt = clock.instant();
        chat.archive(actor.getId(), archivedAt);
        sessions.saveAndFlush(chat);
        audit.recordPrincipalSnapshot(actor, actor.getOrganizationId(),
                AuditEventType.CHAT_ARCHIVED,
                () -> Map.of("chatId", chatId.toString(),
                    "archivedAt", archivedAt.toString(), "retentionMode", "AUDIT_PRESERVED"));
    }

    @Transactional
    public void restore(UUID chatId, SafeAiUserPrincipal actor) {
        requireTenantUser(actor);
        requirePersistedMembership(actor);
        mutex.lockChat(chatId);
        ChatSessionEntity chat = ownedAvailableForUpdate(chatId, actor);
        if (chat.getArchivedAt() == null) return; // idempotent retry
        chat.restore(clock.instant());
        sessions.saveAndFlush(chat);
        audit.recordPrincipalSnapshot(actor, actor.getOrganizationId(),
                AuditEventType.CHAT_RESTORED,
                () -> Map.of("chatId", chatId.toString()));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ChatErasureCapabilityResponse capability(UUID chatId, SafeAiUserPrincipal actor) {
        requireTenantUser(actor);
        requirePersistedAdmin(actor);
        ChatRow chat = ownedRow(chatId, actor, false);
        String reason = !retentionApproved ? "RETENTION_NOT_APPROVED"
                : (!acceptEnabled || !executorEnabled) ? "FEATURE_DISABLED"
                : !"AVAILABLE".equals(chat.erasureState()) ? "ALREADY_ERASING"
                : !chat.archived() ? "ARCHIVE_FIRST"
                : preconditions.hasRetentionHold(chatId) ? "RETENTION_HOLD"
                : preconditions.hasInFlightTurn(chatId) ? "ACTIVE_AI_OPERATION"
                : preconditions.exceedsAtomicErasureLimit(chatId) ? "CHAT_TOO_LARGE_FOR_ATOMIC_ERASURE"
                : !ErasureAdmissionPolicy.allowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT) ? "POLICY_PAUSED"
                : "ENABLED";
        return new ChatErasureCapabilityResponse("ENABLED".equals(reason), reason, chat.version());
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ChatErasureOperationResponse acceptErasure(
            UUID chatId, long expectedVersion, String idempotencyKey,
            ChatErasureRequest confirmation, SafeAiUserPrincipal actor) {
        requireTenantUser(actor);
        if (!acceptEnabled || !executorEnabled || !retentionApproved) {
            throw new ConflictException("Стирание содержимого чатов не разрешено конфигурацией сервера.");
        }
        if (expectedVersion < 0 || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9._:-]{12,128}")) {
            throw new BadRequestException("Недопустимые If-Match или Idempotency-Key.");
        }
        if (confirmation == null || !"УДАЛИТЬ НАВСЕГДА".equals(confirmation.confirmationPhrase())
                || confirmation.confirmationTitle() == null) {
            throw new BadRequestException("Не подтверждено необратимое стирание содержимого чата.");
        }
        // Validate persisted role under the organization/user SHARE locks first,
        // preserving the existing identity-before-chat lock hierarchy.
        requirePersistedAdmin(actor);
        // Always serialize with first-turn reservation and manual title changes.
        mutex.lockChat(chatId);
        // Obtain a scoped row lock before accepting a new operation or replay.
        ChatRow chat = ownedRow(chatId, actor, true);
        List<Operation> prior = jdbc.query("""
                SELECT * FROM public.chat_erasure_operations_v86
                WHERE organization_id=? AND idempotency_key=?
                """, this::mapOperation, actor.getOrganizationId(), idempotencyKey);
        String requestHash = requestDigest(chatId, expectedVersion, confirmation);
        if (!prior.isEmpty()) {
            Operation priorOperation = prior.getFirst();
            if (!priorOperation.chatId().equals(chatId) || priorOperation.expectedVersion() != expectedVersion
                    || !priorOperation.ownerUserId().equals(actor.getId())
                || !priorOperation.requestSha256().equals(requestHash)) {
                throw new ConflictException("Idempotency-Key уже использован для другого удаления.");
            }
            return priorOperation.response();
        }
        if (!"AVAILABLE".equals(chat.erasureState()) || !chat.archived()) {
            throw new ConflictException("Сначала архивируйте чат; уже стираемый чат повторно удалить нельзя.");
        }
        if (chat.version() != expectedVersion) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                "Чат изменился: повторно подтвердите стирание после обновления страницы.");
        }
        if (!chat.title().equals(confirmation.confirmationTitle())) {
            throw new ConflictException("Название чата изменилось или подтверждено неверно.");
        }
        if (preconditions.hasRetentionHold(chatId)) {
            throw new ConflictException("Удаление запрещено действующим ограничением хранения.");
        }
        rejectInFlightTurn(chatId);
        if (preconditions.exceedsAtomicErasureLimit(chatId)) {
            // Reject BEFORE durable acceptance. Requeueing an impossible-sized
            // operation would otherwise produce a permanent NEEDS_REVIEW loop.
            throw new ConflictException(
                "Чат превышает проверенный атомарный лимит стирания. "
                + "Требуется отдельный утверждённый механизм крупного удаления.");
        }
        ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT);
        UUID opId = UUID.randomUUID();
        try {
            if (jdbc.update("""
                INSERT INTO public.chat_erasure_operations_v86
                    (id,chat_id,organization_id,owner_user_id,requested_by_user_id,
                     idempotency_key,expected_version,request_sha256)
                VALUES(?,?,?,?,?,?,?,?)
                """, opId, chatId, actor.getOrganizationId(), actor.getId(), actor.getId(),
                idempotencyKey, expectedVersion, requestHash) != 1) {
                throw new IllegalStateException("ERASURE_INTENT_NOT_DURABLE");
            }
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ConflictException("Idempotency-Key уже использован или операция для чата существует.");
        }
        if (jdbc.update("""
                UPDATE public.chat_sessions SET erasure_state='ERASING',
                    updated_at=GREATEST(updated_at, clock_timestamp()), version=version+1
                WHERE id=? AND organization_id=? AND user_id=?
                  AND archived_at IS NOT NULL AND erasure_state='AVAILABLE' AND version=?
                """, chatId, actor.getOrganizationId(), actor.getId(), expectedVersion) != 1) {
            throw new ConflictException("Чат изменился во время подтверждения стирания.");
        }
        audit.recordPrincipalSnapshot(actor, actor.getOrganizationId(),
                AuditEventType.CHAT_CONTENT_ERASURE_REQUESTED,
                () -> Map.of("chatId", chatId.toString(), "operationId", opId.toString(),
                        "retentionMode", "EVIDENCE_PRESERVED_CONTENT_ERASURE"));
        return erasureStatus(chatId, actor);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ChatErasureOperationResponse erasureStatus(UUID chatId, SafeAiUserPrincipal actor) {
        requireTenantUser(actor);
        requirePersistedAdmin(actor);
        List<Operation> ops = jdbc.query("""
                SELECT * FROM public.chat_erasure_operations_v86
                WHERE chat_id=? AND organization_id=? AND owner_user_id=?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, this::mapOperation, chatId, actor.getOrganizationId(), actor.getId());
        if (ops.isEmpty()) throw new ResourceNotFoundException("Операция стирания не найдена.");
        return ops.getFirst().response();
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public List<ChatErasureOperationResponse> listErasureOperations(SafeAiUserPrincipal actor) {
        requireTenantUser(actor);
        requirePersistedAdmin(actor);
        return jdbc.query("""
                SELECT * FROM public.chat_erasure_operations_v86
                WHERE organization_id=? AND owner_user_id=?
                ORDER BY created_at DESC,id DESC LIMIT 100
                """, this::mapOperation, actor.getOrganizationId(), actor.getId())
                .stream().map(Operation::response).toList();
    }

    private ChatSessionEntity ownedAvailableForUpdate(UUID chatId, SafeAiUserPrincipal actor) {
        return sessions.findOwnedAvailableForUpdate(chatId, actor.getId(),
                actor.getOrganizationId(), ChatErasureState.AVAILABLE)
                .orElseThrow(ChatLifecycleService::notFound);
    }

    private ChatRow ownedRow(UUID chatId, SafeAiUserPrincipal actor, boolean forUpdate) {
        String sql = """
                SELECT id,title,version,archived_at,erasure_state FROM public.chat_sessions
                WHERE id=? AND organization_id=? AND user_id=?
                """ + (forUpdate ? " FOR UPDATE" : "");
        List<ChatRow> rows = jdbc.query(sql, (rs, index) ->
                new ChatRow(rs.getString("title"), rs.getLong("version"),
                        rs.getTimestamp("archived_at") != null, rs.getString("erasure_state")),
                chatId, actor.getOrganizationId(), actor.getId());
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private void rejectInFlightTurn(UUID chatId) {
        if (preconditions.hasInFlightTurn(chatId)) {
            throw new ConflictException("В чате есть незавершённая AI-операция. Дождитесь её терминального статуса.");
        }
    }

    private void requirePersistedMembership(SafeAiUserPrincipal actor) {
        List<String> roles = persistedRoles(actor);
        if ((!roles.contains("ADMIN") && !roles.contains("USER"))
            || roles.contains("SUPER_ADMIN")) {
            throw new ForbiddenOperationException("Доступ к чату изменён или организация отключена.");
        }
    }

    private void requirePersistedAdmin(SafeAiUserPrincipal actor) {
        List<String> roles = persistedRoles(actor);
        if (!roles.contains("ADMIN") || roles.contains("SUPER_ADMIN")) {
            throw new ForbiddenOperationException("Права администратора изменены или организация отключена.");
        }
    }

    private List<String> persistedRoles(SafeAiUserPrincipal actor) {
        // Match the R1a authority check: persisted membership alone does not
        // authorize a JWT from an obsolete user or organization auth epoch.
        // Keep identity-before-chat lock order and SHARE locks on live rows.
        return jdbc.query("""
                SELECT r.name FROM public.users u
                JOIN public.organizations o ON o.id=u.organization_id AND o.enabled
                JOIN public.user_roles ur ON ur.user_id=u.id
                JOIN public.roles r ON r.id=ur.role_id
                WHERE u.id=? AND u.organization_id=? AND u.enabled
                  AND u.token_version=? AND o.auth_version=?
                FOR SHARE OF u,ur,o
                """, (rs, index) -> rs.getString(1), actor.getId(), actor.getOrganizationId(),
                actor.getTokenVersion(), actor.getOrganizationAuthVersion());
    }

    private static void requireTenantUser(SafeAiUserPrincipal actor) {
        // SafeAiUserPrincipal's constructor already rejects null id/orgId.
        // A null principal is still rejected before any database access.
        Objects.requireNonNull(actor, "actor");
    }

    private Operation mapOperation(ResultSet rs, int index) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new Operation(rs.getObject("id", UUID.class), rs.getObject("chat_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class), rs.getLong("expected_version"),
                rs.getString("request_sha256"),rs.getString("state"), rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant(), rs.getString("last_error_code"));
    }

    private record ChatRow(String title, long version, boolean archived, String erasureState) { }
    private record Operation(UUID id, UUID chatId, UUID ownerUserId, long expectedVersion,
                             String requestSha256, String state, Instant createdAt,
                             Instant completedAt, String lastErrorCode) {
        ChatErasureOperationResponse response() {
            return new ChatErasureOperationResponse(id, chatId, state, createdAt,
                    completedAt, lastErrorCode);
        }
    }
    private static String requestDigest(UUID chatId, long version, ChatErasureRequest confirmation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String component : List.of("SafeAI:chat-content-erasure:v86",
                    chatId.toString(), Long.toString(version),
                    confirmation.confirmationTitle(), confirmation.confirmationPhrase())) {
                byte[] value = component.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Чат не найден.");
    }
}
