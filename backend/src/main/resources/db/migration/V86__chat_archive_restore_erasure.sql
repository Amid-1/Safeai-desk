-- SafeAI-Desk/backend/src/main/resources/db/migration/V86__chat_archive_restore_erasure.sql
-- V86: forward-only chat archive recovery and irreversible CONTENT erasure.
-- IMPORTANT: evidence (AI provider attempts, quota, audit, model decisions,
-- RAG passports, immutable hashes/IDs) is deliberately retained.
-- Never claim physical deletion of the chat session or accounting ledger.
-- Feature-gated execution requires approved retention policy.

DO
$preflight$
    BEGIN
        IF to_regclass('public.flyway_schema_history') IS NULL OR
           NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version = '85' AND success) THEN
            RAISE EXCEPTION 'V86 requires successful Flyway V85';
        END IF;
        -- Dedicated identities must be provisioned by a DBA BEFORE Flyway, never
-- created with embedded passwords or silently inherited by safeai_app.
        IF NOT EXISTS (SELECT 1
                       FROM pg_roles
                       WHERE rolname = 'safeai_chat_erasure_worker'
                         AND rolcanlogin
                         AND NOT rolsuper
                         AND NOT rolbypassrls)
            OR NOT EXISTS (SELECT 1
                           FROM pg_roles
                           WHERE rolname = 'safeai_chat_erasure_operator'
                             AND NOT rolcanlogin
                             AND NOT rolsuper
                             AND NOT rolbypassrls)
            OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'safeai_app' AND NOT rolsuper)
            OR pg_has_role('safeai_app', 'safeai_chat_erasure_worker', 'member')
            OR pg_has_role('safeai_app', 'safeai_chat_erasure_operator', 'member') THEN
            RAISE EXCEPTION 'V86: provision isolated worker/operator roles; safeai_app must not inherit them';
        END IF;
        IF session_user IN ('safeai_app', 'safeai_chat_erasure_worker') THEN
            RAISE EXCEPTION 'V86: Flyway requires a distinct migrator identity';
        END IF;
        IF to_regclass('public.chat_sessions') IS NULL
            OR to_regclass('public.chat_messages') IS NULL
            OR to_regclass('public.chat_turns') IS NULL
            OR to_regclass('public.knowledge_retrieval_runs') IS NULL
            OR to_regclass('public.audit_outbox') IS NULL
            OR NOT EXISTS (SELECT 1
                           FROM pg_trigger
                           WHERE tgrelid = 'public.chat_messages'::regclass
                             AND tgname = 'trg_completed_chat_message_update_v67'
                             AND tgenabled IN ('O', 'A')) OR NOT EXISTS (SELECT 1
                                                                         FROM pg_trigger
                                                                         WHERE tgrelid = 'public.knowledge_retrieval_runs'::regclass
                                                                           AND tgname = 'trg_knowledge_retrieval_runs_immutable_v44'
                                                                           AND tgenabled IN ('O', 'A')) THEN
            RAISE EXCEPTION 'V86 preflight failed: required immutable evidence controls missing';
        END IF;
    END;
$preflight$;

ALTER TABLE public.chat_sessions
    ADD COLUMN erasure_state varchar(16) NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN erased_at     timestamptz,
    ADD CONSTRAINT chk_chat_erasure_state_v86 CHECK (
        erasure_state IN ('AVAILABLE', 'ERASING', 'ERASED')
            AND (erasure_state <> 'ERASED' OR (erased_at IS NOT NULL AND archived_at IS NOT NULL))
            AND (erasure_state = 'ERASED' OR erased_at IS NULL)
            AND (erasure_state <> 'ERASING' OR archived_at IS NOT NULL)
        ) NOT VALID;
-- Existing large-table archive index is built CONCURRENTLY in V87.

CREATE TABLE public.chat_erasure_operations_v86
(
    id                   uuid PRIMARY KEY,
    chat_id              uuid         NOT NULL,
    organization_id      uuid         NOT NULL,
    owner_user_id        uuid         NOT NULL,
    requested_by_user_id uuid         NOT NULL,
    idempotency_key      varchar(128) NOT NULL,
    expected_version     bigint       NOT NULL CHECK (expected_version >= 0),
    request_sha256       char(64)     NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    state                varchar(24)  NOT NULL DEFAULT 'ERASING'
        CHECK (state IN ('ERASING', 'NEEDS_REVIEW', 'BLOCKED_BY_RETENTION', 'ERASED', 'CANCELLED')),
    lease_token          uuid,
    lease_until          timestamptz,
    next_attempt_at      timestamptz  NOT NULL DEFAULT clock_timestamp(),
    attempts             integer      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at           timestamptz  NOT NULL DEFAULT clock_timestamp(),
    updated_at           timestamptz  NOT NULL DEFAULT clock_timestamp(),
    completed_at         timestamptz,
    last_error_code      varchar(64),
    CONSTRAINT fk_chat_erasure_owner_v86
        FOREIGN KEY (chat_id, organization_id, owner_user_id)
            REFERENCES public.chat_sessions (id, organization_id, user_id) ON DELETE RESTRICT,
    -- CANCELLED attempts retain evidence but do not prevent later new intent.
    CONSTRAINT chk_chat_erasure_lease_v86 CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT uq_chat_erasure_request_v86 UNIQUE (organization_id, idempotency_key),
    CONSTRAINT chk_chat_erasure_completed_v86 CHECK (
        (state = 'ERASED' AND completed_at IS NOT NULL)
            OR (state <> 'ERASED' AND completed_at IS NULL)
        )
);
CREATE UNIQUE INDEX uq_chat_erasure_open_chat_v86
    ON public.chat_erasure_operations_v86 (organization_id, chat_id)
    WHERE state <> 'CANCELLED';
CREATE INDEX ix_chat_erasure_pending_v86
    ON public.chat_erasure_operations_v86 (next_attempt_at, id)
    WHERE state = 'ERASING';

-- Legal holds are additive, protected by a separate governance process.
-- This feature does not provide any API to remove or modify them.
CREATE TABLE public.chat_retention_holds_v86
(
    id                  uuid PRIMARY KEY,
    chat_id             uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    reason_code         varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z0-9_]{3,64}$'),
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    released_at         timestamptz,
    release_ticket      varchar(120),
    released_by_db_role name,
    CONSTRAINT chk_chat_hold_release_v86 CHECK (
        (released_at IS NULL AND release_ticket IS NULL AND released_by_db_role IS NULL)
            OR (released_at IS NOT NULL AND release_ticket IS NOT NULL AND released_by_db_role IS NOT NULL)
        ),
    CONSTRAINT fk_chat_retention_hold_v86 FOREIGN KEY (chat_id, organization_id)
        REFERENCES public.chat_sessions (id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT chk_chat_retention_hold_dates_v86 CHECK (
        released_at IS NULL OR released_at >= created_at)
);
CREATE INDEX ix_chat_active_holds_v86 ON public.chat_retention_holds_v86 (chat_id)
    WHERE released_at IS NULL;

-- Serialize a newly imposed hold with the final erasure transaction. A late
-- legal hold can stop ERASING, but it must not appear after terminal erasure.
CREATE FUNCTION public.chat_hold_insert_guard_v86() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_state text;
BEGIN
    SELECT erasure_state
    INTO v_state
    FROM public.chat_sessions
    WHERE id = NEW.chat_id
      AND organization_id = NEW.organization_id FOR SHARE;
    IF v_state IS NULL OR v_state = 'ERASED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 cannot attach hold to missing/erased chat';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_hold_insert_guard_v86
    BEFORE INSERT
    ON public.chat_retention_holds_v86
    FOR EACH ROW
EXECUTE FUNCTION public.chat_hold_insert_guard_v86();
-- No runtime role may clear evidence holds through ordinary DML.
CREATE FUNCTION public.chat_hold_protected_v86() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE'
        AND pg_has_role(session_user, 'safeai_chat_erasure_operator', 'member')
        AND OLD.released_at IS NULL AND NEW.released_at IS NOT NULL
        AND NEW.release_ticket ~ '^[A-Z0-9][A-Z0-9._/-]{7,119}$'
        AND NEW.released_by_db_role = session_user
        AND
       ROW (NEW.id,NEW.chat_id,NEW.organization_id,NEW.reason_code,NEW.created_at) IS NOT DISTINCT FROM ROW (OLD.id,OLD.chat_id,OLD.organization_id,OLD.reason_code,OLD.created_at)
        AND EXISTS (SELECT 1
                    FROM public.chat_sessions s
                    WHERE s.id = OLD.chat_id
                      AND s.organization_id = OLD.organization_id
                      AND s.erasure_state <> 'ERASED') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 retention hold may only be released by privileged operator';
END
$$;
CREATE TRIGGER trg_chat_hold_protected_v86
    BEFORE UPDATE OR DELETE
    ON public.chat_retention_holds_v86
    FOR EACH ROW
EXECUTE FUNCTION public.chat_hold_protected_v86();

CREATE FUNCTION public.chat_erasure_authorized_v86(p_chat uuid)
    RETURNS boolean
    LANGUAGE plpgsql
    SET search_path = pg_catalog,public AS
$$
DECLARE
    v_operation text; v_token text;
BEGIN
    IF session_user <> 'safeai_chat_erasure_worker' THEN RETURN false; END IF;
    v_operation := current_setting('safeai.chat.erasure_operation', true);
    v_token := current_setting('safeai.chat.erasure_token', true);
    IF v_operation IS NULL OR v_token IS NULL
        OR v_operation !~ '^[0-9a-fA-F-]{36}$'
        OR v_token !~ '^[0-9a-fA-F-]{36}$' THEN
        RETURN false;
    END IF;
    RETURN EXISTS (SELECT 1
                   FROM public.chat_erasure_operations_v86 op
                            JOIN public.chat_sessions s ON s.id = op.chat_id AND s.organization_id = op.organization_id
                   WHERE op.id = v_operation::uuid
                     AND op.lease_token = v_token::uuid
                     AND op.lease_until > clock_timestamp()
                     AND op.state = 'ERASING'
                     AND op.chat_id = p_chat
                     AND s.erasure_state = 'ERASING');
END
$$;

CREATE FUNCTION public.chat_erasure_operation_owner_v86(p_operation uuid, p_token uuid)
    RETURNS boolean
    LANGUAGE plpgsql
    SET search_path = pg_catalog,public AS
$$
DECLARE
    v_operation text; v_token text;
BEGIN
    IF session_user <> 'safeai_chat_erasure_worker' THEN RETURN false; END IF;
    v_operation := current_setting('safeai.chat.erasure_operation', true);
    v_token := current_setting('safeai.chat.erasure_token', true);
    RETURN session_user = 'safeai_chat_erasure_worker'
        AND v_operation IS NOT NULL AND v_token IS NOT NULL
        AND v_operation = p_operation::text AND v_token = p_token::text;
END
$$;

-- A durable erasure intent cannot be forged for a live, foreign or held chat.
-- This protects the table even if callers bypass the HTTP controller.
CREATE FUNCTION public.guard_chat_erasure_intent_insert_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.state <> 'ERASING' OR NEW.lease_token IS NOT NULL
        OR NEW.lease_until IS NOT NULL OR NEW.completed_at IS NOT NULL
        OR NEW.attempts <> 0 OR NEW.last_error_code IS NOT NULL
        OR NEW.owner_user_id <> NEW.requested_by_user_id
        OR NEW.request_sha256 !~ '^[0-9a-f]{64}$'
        OR NOT EXISTS (SELECT 1
                       FROM public.chat_sessions s
                       WHERE s.id = NEW.chat_id
                         AND s.organization_id = NEW.organization_id
                         AND s.user_id = NEW.owner_user_id
                         AND s.archived_at IS NOT NULL
                         AND s.erasure_state = 'AVAILABLE'
                         AND s.version = NEW.expected_version)
        OR NOT EXISTS (SELECT 1
                       FROM public.users u
                                JOIN public.user_roles ur ON ur.user_id = u.id
                                JOIN public.roles r ON r.id = ur.role_id
                       WHERE u.id = NEW.requested_by_user_id
                         AND u.organization_id = NEW.organization_id
                         AND u.enabled
                         AND r.name = 'ADMIN')
        OR EXISTS (SELECT 1
                   FROM public.chat_retention_holds_v86 h
                   WHERE h.chat_id = NEW.chat_id
                     AND h.organization_id = NEW.organization_id
                     AND h.released_at IS NULL)
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V86 erasure intent preconditions not satisfied';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_erasure_intent_insert_v86
    BEFORE INSERT
    ON public.chat_erasure_operations_v86
    FOR EACH ROW
EXECUTE FUNCTION public.guard_chat_erasure_intent_insert_v86();

-- Fail closed for ANY outstanding logical or physical execution, including V85
-- ACTIVE permits/leases even when their wall-clock expiry has passed.
CREATE FUNCTION public.chat_has_active_execution_v86(p_chat uuid)
    RETURNS boolean
    LANGUAGE sql
    STABLE SET search_path = pg_catalog,public AS
$$
SELECT EXISTS(SELECT 1
              FROM public.chat_turns t
              WHERE t.session_id = p_chat
                AND t.state IN ('NEW', 'PROCESSING'))
           OR EXISTS(SELECT 1
                     FROM public.model_execution_attempts a
                              JOIN public.model_execution_plans p ON p.id = a.execution_plan_id
                              JOIN public.chat_turns t ON t.id = p.chat_turn_id
                     WHERE t.session_id = p_chat
                       AND a.outcome = 'STARTED')
           OR EXISTS(SELECT 1
                     FROM public.knowledge_rag_execution_permits_v85 p
                              JOIN public.chat_turns t ON t.id = p.chat_turn_id
                     WHERE t.session_id = p_chat
                       AND p.state = 'ACTIVE')
           OR EXISTS(SELECT 1
                     FROM public.knowledge_rag_execution_attempt_leases_v85 l
                              JOIN public.knowledge_rag_execution_permits_v85 p ON p.id = l.permit_id
                              JOIN public.chat_turns t ON t.id = p.chat_turn_id
                     WHERE t.session_id = p_chat
                       AND l.state = 'ACTIVE');
$$;

CREATE FUNCTION public.guard_chat_erasure_operation_v86()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog,public AS
$$
DECLARE
    v_operator boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 erasure-operation evidence is immutable';
    END IF;
    IF ROW (NEW.id,NEW.chat_id,NEW.organization_id,NEW.owner_user_id,
        NEW.requested_by_user_id,NEW.idempotency_key,NEW.expected_version,
        NEW.request_sha256,NEW.created_at) IS DISTINCT FROM ROW (OLD.id,OLD.chat_id,OLD.organization_id,OLD.owner_user_id,
        OLD.requested_by_user_id,OLD.idempotency_key,OLD.expected_version,
        OLD.request_sha256,OLD.created_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 immutable operation identity';
    END IF;
    v_operator := pg_has_role(session_user, 'safeai_chat_erasure_operator', 'member');
    IF v_operator THEN
        -- An authorized operator may recover a reviewed/held operation or cancel
        -- it with an audit ticket, but can NEVER certify content as ERASED.
        IF OLD.state NOT IN ('NEEDS_REVIEW', 'BLOCKED_BY_RETENTION')
            OR NEW.state NOT IN ('ERASING', 'CANCELLED')
            OR OLD.lease_token IS NOT NULL OR NEW.lease_token IS NOT NULL
            OR NEW.lease_until IS NOT NULL OR NEW.completed_at IS NOT NULL
            OR current_setting('safeai.chat.operator_ticket', true) IS NULL
            OR current_setting('safeai.chat.operator_ticket', true)
               !~ '^[A-Z0-9][A-Z0-9._/-]{7,119}$'
            OR NOT EXISTS (SELECT 1
                           FROM public.chat_sessions s
                           WHERE s.id = OLD.chat_id
                             AND s.organization_id = OLD.organization_id
                             AND s.erasure_state = 'ERASING') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 unauthorized operator transition';
        END IF;
        IF NEW.state = 'ERASING' AND (
            EXISTS (SELECT 1
                    FROM public.chat_retention_holds_v86 h
                    WHERE h.chat_id = OLD.chat_id
                      AND h.organization_id = OLD.organization_id
                      AND h.released_at IS NULL)
                OR public.chat_has_active_execution_v86(OLD.chat_id)) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 resume blocked by retention or execution';
        END IF;
        RETURN NEW;
    END IF;
    IF session_user <> 'safeai_chat_erasure_worker' OR OLD.state <> 'ERASING'
        OR NEW.state NOT IN ('ERASING', 'NEEDS_REVIEW', 'BLOCKED_BY_RETENTION', 'ERASED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 only dedicated executor may update operations';
    END IF;
    IF NEW.state <> 'ERASING' THEN
        IF OLD.lease_token IS NULL OR OLD.lease_until <= clock_timestamp()
            OR NOT public.chat_erasure_operation_owner_v86(OLD.id, OLD.lease_token) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 completion requires live fenced worker';
        END IF;
        IF NEW.state = 'ERASED' AND (
            NOT EXISTS (SELECT 1
                        FROM public.chat_sessions s
                        WHERE s.id = OLD.chat_id
                          AND s.organization_id = OLD.organization_id
                          AND s.erasure_state = 'ERASED')
                OR EXISTS (SELECT 1
                           FROM public.chat_messages m
                           WHERE m.session_id = OLD.chat_id
                             AND m.content IS DISTINCT FROM '[СОДЕРЖИМОЕ УДАЛЕНО]')
                OR EXISTS (SELECT 1
                           FROM public.knowledge_retrieval_runs r
                                    JOIN public.chat_turns t ON t.id = r.chat_turn_id
                           WHERE t.session_id = OLD.chat_id
                             AND r.query_text IS DISTINCT FROM '[REDACTED:SHA256_ONLY]')
                OR public.chat_has_active_execution_v86(OLD.chat_id)
            ) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 incomplete erasure cannot be finalized';
        END IF;
        IF NEW.state = 'BLOCKED_BY_RETENTION' AND NOT EXISTS (SELECT 1
                                                              FROM public.chat_retention_holds_v86 h
                                                              WHERE h.chat_id = OLD.chat_id
                                                                AND h.organization_id = OLD.organization_id
                                                                AND h.released_at IS NULL) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 retention block requires hold';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_erasure_op_guard_v86
    BEFORE UPDATE OR DELETE
    ON public.chat_erasure_operations_v86
    FOR EACH ROW
EXECUTE FUNCTION public.guard_chat_erasure_operation_v86();

CREATE FUNCTION public.guard_chat_session_erasure_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.erasure_state <> 'AVAILABLE' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 erasure evidence session cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.erasure_state <> 'AVAILABLE' OR NEW.erased_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 new session must be AVAILABLE';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.erasure_state = 'ERASED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 erased session is immutable';
    END IF;
    IF OLD.erasure_state = 'ERASING' AND NEW.erasure_state = 'AVAILABLE' THEN
        IF NOT pg_has_role(session_user, 'safeai_chat_erasure_operator', 'member')
            OR NEW.title IS DISTINCT FROM OLD.title
            OR NEW.title_source IS DISTINCT FROM OLD.title_source
            OR NEW.archived_at IS DISTINCT FROM OLD.archived_at
            OR NEW.archived_by_user_id IS DISTINCT FROM OLD.archived_by_user_id
            OR NEW.erased_at IS NOT NULL OR NEW.version <> OLD.version + 1
            OR NOT EXISTS (SELECT 1
                           FROM public.chat_erasure_operations_v86 op
                           WHERE op.chat_id = OLD.id
                             AND op.organization_id = OLD.organization_id
                             AND op.state = 'CANCELLED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 invalid operator cancellation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.erasure_state = 'ERASING' THEN
        IF NEW.erasure_state <> 'ERASED'
            OR NOT public.chat_erasure_authorized_v86(OLD.id)
            OR NEW.title <> '[СОДЕРЖИМОЕ УДАЛЕНО]'
            OR NEW.title_source <> 'USER'
            OR NEW.archived_at IS DISTINCT FROM OLD.archived_at
            OR NEW.archived_by_user_id IS DISTINCT FROM OLD.archived_by_user_id
            OR NEW.erased_at IS NULL
            OR NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 session erasure transition denied';
        END IF;
        RETURN NEW;
    END IF;
    -- Enforce the same guard for legacy DELETE /api/chats/{id} clients.
    -- The session row lock conflicts with the FOR SHARE lock obtained by
    -- the V86 chat-turn insert guard, making archive-vs-reservation atomic.
    IF OLD.archived_at IS NULL AND NEW.archived_at IS NOT NULL
        AND public.chat_has_active_execution_v86(OLD.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V86 active AI/RAG execution prevents archive';
    END IF;
    IF NEW.erasure_state = 'ERASING' THEN
        IF OLD.erasure_state <> 'AVAILABLE' OR OLD.archived_at IS NULL
            OR NEW.title IS DISTINCT FROM OLD.title
            OR NEW.title_source IS DISTINCT FROM OLD.title_source
            OR NEW.archived_at IS DISTINCT FROM OLD.archived_at
            OR NEW.archived_by_user_id IS DISTINCT FROM OLD.archived_by_user_id
            OR NEW.erased_at IS NOT NULL OR NEW.version <> OLD.version + 1
            OR NOT EXISTS (SELECT 1
                           FROM public.chat_erasure_operations_v86 o
                           WHERE o.chat_id = OLD.id
                             AND o.organization_id = OLD.organization_id
                             AND o.state = 'ERASING') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 session erasure request missing';
        END IF;
    ELSIF NEW.erasure_state <> 'AVAILABLE' OR NEW.erased_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 invalid session lifecycle transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_session_erasure_v86
    BEFORE INSERT OR UPDATE OR DELETE
    ON public.chat_sessions
    FOR EACH ROW
EXECUTE FUNCTION public.guard_chat_session_erasure_v86();

-- Disallow new writes after archiving or erasure admission. A PROCESSING turn
-- must become terminal before an erasure may be accepted.
CREATE FUNCTION public.chat_require_writable_session_v86(p_session uuid)
    RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM 1
    FROM public.chat_sessions s
    WHERE s.id = p_session
      AND s.erasure_state = 'AVAILABLE'
      AND s.archived_at IS NULL FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 archived/erasing chat rejects new writes';
    END IF;
END
$$;
CREATE FUNCTION public.chat_messages_erasure_guard_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_state text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM public.chat_require_writable_session_v86(NEW.session_id);
        RETURN NEW;
    END IF;
    -- An ERASING session is necessarily archived. Keep its distinct state so
    -- ONLY a live, separately authenticated worker may perform content-only
    -- redaction. All other archived sessions stay frozen.
    SELECT CASE
               WHEN erasure_state = 'ERASING' THEN 'ERASING'
               WHEN erasure_state = 'AVAILABLE' AND archived_at IS NULL THEN 'AVAILABLE'
               ELSE erasure_state || ':FROZEN'
               END
    INTO v_state
    FROM public.chat_sessions
    WHERE id = OLD.session_id;
    IF TG_OP = 'DELETE' THEN
        IF v_state IS DISTINCT FROM 'AVAILABLE' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 cannot delete erased chat evidence';
        END IF;
        RETURN OLD;
    END IF;
    IF v_state = 'ERASING' AND public.chat_erasure_authorized_v86(OLD.session_id)
        AND NEW.content = '[СОДЕРЖИМОЕ УДАЛЕНО]'
        AND (to_jsonb(NEW) - 'content') = (to_jsonb(OLD) - 'content') THEN
        RETURN NEW;
    END IF;
    IF v_state IS DISTINCT FROM 'AVAILABLE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 erased chat messages are immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_erasure_message_guard_v86
    BEFORE INSERT OR UPDATE OR DELETE
    ON public.chat_messages
    FOR EACH ROW
EXECUTE FUNCTION public.chat_messages_erasure_guard_v86();

-- V67 still forbids every completed-message update except an authenticated,
-- one-way content-only erasure. No trigger is disabled or removed.
CREATE OR REPLACE FUNCTION public.guard_completed_chat_message_update_v67()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status = 'COMPLETED' AND NOT (
        NEW.content = '[СОДЕРЖИМОЕ УДАЛЕНО]'
            AND OLD.content IS DISTINCT FROM NEW.content
            AND (to_jsonb(NEW) - 'content') = (to_jsonb(OLD) - 'content')
            AND public.chat_erasure_authorized_v86(OLD.session_id)
        ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V67 completed chat message evidence is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION public.chat_turn_erasure_guard_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_state text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM public.chat_require_writable_session_v86(NEW.session_id);
        RETURN NEW;
    END IF;
    -- An ERASING session is necessarily archived. Keep its distinct state so
    -- ONLY a live, separately authenticated worker may perform content-only
    -- redaction. All other archived sessions stay frozen.
    SELECT CASE
               WHEN erasure_state = 'ERASING' THEN 'ERASING'
               WHEN erasure_state = 'AVAILABLE' AND archived_at IS NULL THEN 'AVAILABLE'
               ELSE erasure_state || ':FROZEN'
               END
    INTO v_state
    FROM public.chat_sessions
    WHERE id = OLD.session_id;
    IF v_state IS DISTINCT FROM 'AVAILABLE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V86 chat turn is frozen after erasure acceptance';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_turn_erasure_guard_v86
    BEFORE INSERT OR UPDATE OR DELETE
    ON public.chat_turns
    FOR EACH ROW
EXECUTE FUNCTION public.chat_turn_erasure_guard_v86();

CREATE FUNCTION public.chat_route_erasure_guard_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM public.chat_require_writable_session_v86(NEW.chat_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_route_erasure_guard_v86
    BEFORE INSERT
    ON public.model_route_decisions
    FOR EACH ROW
EXECUTE FUNCTION public.chat_route_erasure_guard_v86();

-- Other durable execution entry points must also stop accepting writes after
-- erasure admission. Immutable old evidence is never touched.
CREATE FUNCTION public.chat_other_evidence_insert_guard_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_chat uuid;
BEGIN
    IF TG_TABLE_NAME = 'model_execution_attempts' THEN
        SELECT t.session_id
        INTO v_chat
        FROM public.model_execution_plans p
                 JOIN public.chat_turns t ON t.id = p.chat_turn_id
        WHERE p.id = NEW.execution_plan_id;
    ELSIF TG_TABLE_NAME = 'knowledge_retrieval_runs' THEN
        IF NEW.chat_turn_id IS NULL THEN RETURN NEW; END IF;
        SELECT t.session_id INTO v_chat FROM public.chat_turns t WHERE t.id = NEW.chat_turn_id;
    ELSIF TG_TABLE_NAME = 'knowledge_answer_passports' THEN
        v_chat := NEW.chat_id;
    ELSE
        RAISE EXCEPTION 'V86 unsupported evidence insert source';
    END IF;
    IF v_chat IS NOT NULL THEN PERFORM public.chat_require_writable_session_v86(v_chat); END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_attempt_entry_v86
    BEFORE INSERT
    ON public.model_execution_attempts
    FOR EACH ROW
EXECUTE FUNCTION public.chat_other_evidence_insert_guard_v86();
CREATE TRIGGER trg_chat_retrieval_entry_v86
    BEFORE INSERT
    ON public.knowledge_retrieval_runs
    FOR EACH ROW
EXECUTE FUNCTION public.chat_other_evidence_insert_guard_v86();
CREATE TRIGGER trg_chat_passport_entry_v86
    BEFORE INSERT
    ON public.knowledge_answer_passports
    FOR EACH ROW
EXECUTE FUNCTION public.chat_other_evidence_insert_guard_v86();

-- V44 provenance immutability remains mandatory. The sole exception is
-- redaction of pre-V76 plaintext query_text tied to a live, fenced chat
-- erasure operation; all other columns remain bit-identical.
CREATE OR REPLACE FUNCTION public.reject_knowledge_retrieval_provenance_update_v44()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_chat uuid;
BEGIN
    IF TG_TABLE_NAME = 'knowledge_retrieval_runs' THEN
        SELECT t.session_id
        INTO v_chat
        FROM public.chat_turns t
        WHERE t.id = OLD.chat_turn_id;
        IF v_chat IS NOT NULL
            AND NEW.query_text = '[REDACTED:SHA256_ONLY]'
            AND (to_jsonb(NEW) - 'query_text') = (to_jsonb(OLD) - 'query_text')
            AND public.chat_erasure_authorized_v86(v_chat) THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514',
        MESSAGE = format('%s rows are immutable provenance', tg_table_name);
END
$$;

CREATE FUNCTION public.chat_erasure_terminal_audit_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_now timestamptz; v_event text;
BEGIN
    IF OLD.state IS NOT DISTINCT FROM NEW.state
        OR NEW.state NOT IN ('ERASED', 'NEEDS_REVIEW', 'BLOCKED_BY_RETENTION') THEN
        RETURN NEW;
    END IF;
    v_now := clock_timestamp();
    v_event := CASE NEW.state
                   WHEN 'ERASED' THEN 'CHAT_CONTENT_ERASURE_COMPLETED'
                   WHEN 'NEEDS_REVIEW' THEN 'CHAT_CONTENT_ERASURE_REVIEW_REQUIRED'
                   ELSE 'CHAT_CONTENT_ERASURE_RETENTION_BLOCKED' END;
    INSERT INTO public.audit_outbox
    (id, actor_user_id, organization_id, event_type, details,
     occurred_at, created_at, attempt_count, next_attempt_at)
    VALUES (gen_random_uuid(), NULL, NEW.organization_id, v_event,
            jsonb_build_object('operationId', NEW.id, 'chatId', NEW.chat_id,
                               'state', NEW.state, 'lastErrorCode', NEW.last_error_code),
            v_now, v_now, 0, v_now);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_erasure_terminal_audit_v86
    AFTER UPDATE OF state
    ON public.chat_erasure_operations_v86
    FOR EACH ROW
    WHEN (OLD.state IS DISTINCT FROM NEW.state)
EXECUTE FUNCTION public.chat_erasure_terminal_audit_v86();

INSERT INTO public.audit_event_types(name, description)
VALUES ('CHAT_RESTORED', 'User restored an archived chat'),
       ('CHAT_CONTENT_ERASURE_REQUESTED', 'Chat content erasure accepted'),
       ('CHAT_CONTENT_ERASURE_COMPLETED', 'Chat content irreversibly redacted; evidence retained'),
       ('CHAT_CONTENT_ERASURE_REVIEW_REQUIRED', 'Chat content erasure requires manual review'),
       ('CHAT_CONTENT_ERASURE_RETENTION_BLOCKED', 'Chat content erasure held by retention'),
       ('CHAT_CONTENT_ERASURE_RESUMED', 'Privileged operator resumed chat erasure'),
       ('CHAT_CONTENT_ERASURE_CANCELLED', 'Privileged operator cancelled chat erasure'),
       ('CHAT_RETENTION_HOLD_RELEASED', 'Privileged operator released legal hold'),
       ('CHAT_ERASURE_OPERATOR_APPROVED', 'Named operator approved a reviewed destructive action')
ON CONFLICT(name) DO NOTHING;

COMMENT ON TABLE public.chat_erasure_operations_v86 IS
    'Immutable erasure intents; retained evidence and completed AI accounting are NOT physically deleted.';
COMMENT ON COLUMN public.chat_sessions.erasure_state IS
    'AVAILABLE, ERASING, ERASED. ERASED sessions are immutable tombstones, not physically dropped.';

-- V85 admission barrier: neither a new RAG permit nor physical attempt may be
-- attached after the chat has been archived/accepted for erasure.
CREATE FUNCTION public.chat_rag_execution_entry_guard_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_chat uuid;
BEGIN
    IF TG_TABLE_NAME = 'knowledge_rag_execution_permits_v85' THEN
        SELECT t.session_id INTO v_chat FROM public.chat_turns t WHERE t.id = NEW.chat_turn_id;
    ELSE
        SELECT t.session_id
        INTO v_chat
        FROM public.knowledge_rag_execution_permits_v85 p
                 JOIN public.chat_turns t ON t.id = p.chat_turn_id
        WHERE p.id = NEW.permit_id;
    END IF;
    IF v_chat IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',MESSAGE = 'V86 missing chat linkage for RAG execution';
    END IF;
    PERFORM public.chat_require_writable_session_v86(v_chat);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_rag_permit_entry_v86
    BEFORE INSERT
    ON public.knowledge_rag_execution_permits_v85
    FOR EACH ROW
EXECUTE FUNCTION public.chat_rag_execution_entry_guard_v86();
CREATE TRIGGER trg_chat_rag_attempt_entry_v86
    BEFORE INSERT
    ON public.knowledge_rag_execution_attempt_leases_v85
    FOR EACH ROW
EXECUTE FUNCTION public.chat_rag_execution_entry_guard_v86();

-- Operator controls: dedicated NOLOGIN group with individual operator logins.
-- SECURITY DEFINER is owned by Flyway migrator; app and worker have NO EXECUTE.
-- Each action is transactional and emits durable audit_outbox evidence.
-- Immutable two-person approvals. No direct DML is granted to operators.
-- The database, not an HTTP header, authenticates the distinct login roles.
CREATE TABLE public.chat_erasure_operator_approvals_v86
(
    action            varchar(16)  NOT NULL CHECK (action IN ('RELEASE_HOLD', 'RESUME', 'CANCEL')),
    subject_id        uuid         NOT NULL,
    ticket            varchar(120) NOT NULL CHECK (ticket ~ '^[A-Z0-9][A-Z0-9._/-]{7,119}$'),
    operator_db_login name         NOT NULL,
    approved_at       timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (action, subject_id, ticket, operator_db_login)
);
CREATE FUNCTION public.chat_operator_approval_immutable_v86()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514',MESSAGE = 'V86 operator approvals cannot be mutated';
END
$$;
CREATE TRIGGER trg_chat_operator_approval_immutable_v86
    BEFORE UPDATE OR DELETE
    ON public.chat_erasure_operator_approvals_v86
    FOR EACH ROW
EXECUTE FUNCTION public.chat_operator_approval_immutable_v86();
CREATE FUNCTION public.chat_operator_approve_v86(p_action text, p_subject uuid, p_ticket text)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER SET search_path = pg_catalog,public AS
$$
DECLARE
    v_org uuid;
BEGIN
    IF NOT pg_has_role(session_user, 'safeai_chat_erasure_operator', 'member')
        OR p_action NOT IN ('RELEASE_HOLD', 'RESUME', 'CANCEL')
        OR p_ticket !~ '^[A-Z0-9][A-Z0-9._/-]{7,119}$' THEN
        RAISE EXCEPTION USING ERRCODE = '42501',MESSAGE = 'V86 operator approval invalid';
    END IF;
    IF p_action = 'RELEASE_HOLD' THEN
        SELECT organization_id INTO v_org FROM public.chat_retention_holds_v86 WHERE id = p_subject;
    ELSE
        SELECT organization_id INTO v_org FROM public.chat_erasure_operations_v86 WHERE id = p_subject;
    END IF;
    IF v_org IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '02000',MESSAGE = 'V86 operator target missing';
    END IF;
    INSERT INTO public.chat_erasure_operator_approvals_v86
        (action, subject_id, ticket, operator_db_login)
    VALUES (p_action, p_subject, p_ticket, session_user)
    ON CONFLICT DO NOTHING;
    IF NOT FOUND THEN RETURN; END IF;
    INSERT INTO public.audit_outbox
        (id, actor_user_id, organization_id, event_type, details, created_at)
    VALUES (gen_random_uuid(), NULL, v_org, 'CHAT_ERASURE_OPERATOR_APPROVED',
            jsonb_build_object('action', p_action, 'subjectId', p_subject, 'ticket', p_ticket,
                               'operatorDbRole', session_user), clock_timestamp());
END
$$;
CREATE FUNCTION public.chat_operator_two_approvals_v86(
    p_action text, p_subject uuid, p_ticket text)
    RETURNS boolean
    LANGUAGE sql
    SECURITY DEFINER SET search_path = pg_catalog,public AS
$$
SELECT EXISTS(SELECT 1
              FROM public.chat_erasure_operator_approvals_v86
              WHERE action = p_action
                AND subject_id = p_subject
                AND ticket = p_ticket
                AND operator_db_login = session_user
                AND approved_at > clock_timestamp() - interval '24 hours')
           AND (SELECT count(*)
                FROM public.chat_erasure_operator_approvals_v86 a
                         JOIN pg_catalog.pg_roles r ON r.rolname = a.operator_db_login
                WHERE a.action = p_action
                  AND a.subject_id = p_subject
                  AND a.ticket = p_ticket
                  AND a.approved_at > clock_timestamp() - interval '24 hours'
                  AND r.rolcanlogin
                  AND pg_has_role(r.rolname, 'safeai_chat_erasure_operator', 'member')) >= 2;
$$;
REVOKE ALL ON public.chat_erasure_operator_approvals_v86 FROM PUBLIC;
REVOKE ALL ON FUNCTION public.chat_operator_approve_v86(text,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.chat_operator_approve_v86(text,uuid,text) TO safeai_chat_erasure_operator;
REVOKE ALL ON FUNCTION public.chat_operator_two_approvals_v86(text,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.chat_operator_two_approvals_v86(text,uuid,text) TO safeai_chat_erasure_operator;

CREATE FUNCTION public.chat_operator_release_hold_v86(p_hold uuid, p_ticket text)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER SET search_path = pg_catalog,public AS
$$
DECLARE
    v_hold public.chat_retention_holds_v86%ROWTYPE; v_now timestamptz;
BEGIN
    IF NOT pg_has_role(session_user, 'safeai_chat_erasure_operator', 'member')
        OR p_ticket !~ '^[A-Z0-9][A-Z0-9._/-]{7,119}$'
        OR NOT public.chat_operator_two_approvals_v86('RELEASE_HOLD', p_hold, p_ticket) THEN
        RAISE EXCEPTION USING ERRCODE = '42501',MESSAGE = 'V86 two independent approvals required';
    END IF;
    SELECT * INTO v_hold FROM public.chat_retention_holds_v86 WHERE id = p_hold FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE = '02000',MESSAGE = 'V86 hold not found'; END IF;
    IF v_hold.released_at IS NOT NULL THEN RETURN; END IF;
    PERFORM 1
    FROM public.chat_sessions
    WHERE id = v_hold.chat_id
      AND organization_id = v_hold.organization_id
      AND erasure_state <> 'ERASED' FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE = '23514',MESSAGE = 'V86 erased chat hold cannot release'; END IF;
    v_now := clock_timestamp();
    UPDATE public.chat_retention_holds_v86
    SET released_at=v_now,
        release_ticket=p_ticket,
        released_by_db_role=session_user
    WHERE id = p_hold;
    INSERT INTO public.audit_outbox
        (id, actor_user_id, organization_id, event_type, details, created_at)
    VALUES (gen_random_uuid(), NULL, v_hold.organization_id, 'CHAT_RETENTION_HOLD_RELEASED',
            jsonb_build_object('holdId', p_hold, 'chatId', v_hold.chat_id, 'ticket', p_ticket,
                               'operatorDbRole', session_user), v_now);
END
$$;

CREATE FUNCTION public.chat_operator_resolve_erasure_v86(
    p_operation uuid, p_action text, p_ticket text)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER SET search_path = pg_catalog,public AS
$$
DECLARE
    v_op public.chat_erasure_operations_v86%ROWTYPE; v_state varchar(16);
BEGIN
    IF NOT pg_has_role(session_user, 'safeai_chat_erasure_operator', 'member')
        OR p_action NOT IN ('RESUME', 'CANCEL')
        OR p_ticket !~ '^[A-Z0-9][A-Z0-9._/-]{7,119}$'
        OR NOT public.chat_operator_two_approvals_v86(p_action, p_operation, p_ticket) THEN
        RAISE EXCEPTION USING ERRCODE = '42501',MESSAGE = 'V86 operator action requires two independent approvals';
    END IF;
    SELECT * INTO v_op FROM public.chat_erasure_operations_v86 WHERE id = p_operation FOR UPDATE;
    IF NOT FOUND OR v_op.state NOT IN ('NEEDS_REVIEW', 'BLOCKED_BY_RETENTION')
        OR v_op.lease_token IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',MESSAGE = 'V86 operation is not reviewable';
    END IF;
    SELECT erasure_state
    INTO v_state
    FROM public.chat_sessions
    WHERE id = v_op.chat_id
      AND organization_id = v_op.organization_id FOR UPDATE;
    IF v_state IS DISTINCT FROM 'ERASING' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',MESSAGE = 'V86 chat no longer quarantined';
    END IF;
    IF p_action = 'RESUME' THEN
        IF EXISTS(SELECT 1
                  FROM public.chat_retention_holds_v86 h
                  WHERE h.chat_id = v_op.chat_id
                    AND h.organization_id = v_op.organization_id
                    AND h.released_at IS NULL)
            OR public.chat_has_active_execution_v86(v_op.chat_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',MESSAGE = 'V86 unresolved hold/execution';
        END IF;
    END IF;
    PERFORM set_config('safeai.chat.operator_ticket', p_ticket, true);
    UPDATE public.chat_erasure_operations_v86
    SET state=CASE WHEN p_action = 'CANCEL' THEN 'CANCELLED' ELSE 'ERASING' END,
        lease_token=NULL,
        lease_until=NULL,
        last_error_code=NULL,
        next_attempt_at=clock_timestamp(),
        updated_at=clock_timestamp()
    WHERE id = p_operation;
    IF p_action = 'CANCEL' THEN
        UPDATE public.chat_sessions
        SET erasure_state='AVAILABLE',
            version=version + 1,
            updated_at=clock_timestamp()
        WHERE id = v_op.chat_id
          AND organization_id = v_op.organization_id;
    END IF;
    INSERT INTO public.audit_outbox
        (id, actor_user_id, organization_id, event_type, details, created_at)
    VALUES (gen_random_uuid(), NULL, v_op.organization_id,
            CASE
                WHEN p_action = 'CANCEL' THEN 'CHAT_CONTENT_ERASURE_CANCELLED'
                ELSE 'CHAT_CONTENT_ERASURE_RESUMED' END,
            jsonb_build_object('chatId', v_op.chat_id, 'operationId', p_operation,
                               'ticket', p_ticket, 'operatorDbRole', session_user), clock_timestamp());
END
$$;

REVOKE ALL ON FUNCTION public.chat_operator_release_hold_v86(uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.chat_operator_resolve_erasure_v86(uuid,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.chat_operator_release_hold_v86(uuid,text) TO safeai_chat_erasure_operator;
GRANT EXECUTE ON FUNCTION public.chat_operator_resolve_erasure_v86(uuid,text,text) TO safeai_chat_erasure_operator;

-- Separate runtime DB credentials. Application role can submit intents, but
-- cannot claim leases, modify legal holds, or execute the actual redaction.
REVOKE ALL ON public.chat_erasure_operations_v86 FROM PUBLIC;
REVOKE ALL ON public.chat_retention_holds_v86 FROM PUBLIC;
GRANT SELECT, INSERT ON public.chat_erasure_operations_v86 TO safeai_app;
GRANT SELECT ON public.chat_retention_holds_v86 TO safeai_app;
GRANT SELECT, UPDATE ON public.chat_erasure_operations_v86 TO safeai_chat_erasure_worker;
GRANT SELECT ON public.chat_retention_holds_v86 TO safeai_chat_erasure_worker;
GRANT SELECT ON public.chat_sessions,public.chat_turns,public.chat_messages,
    public.knowledge_retrieval_runs,public.model_execution_plans,
    public.model_execution_attempts,public.knowledge_rag_execution_permits_v85,
    public.knowledge_rag_execution_attempt_leases_v85 TO safeai_chat_erasure_worker;
GRANT UPDATE (title, title_source, erasure_state, erased_at, updated_at, version)
    ON public.chat_sessions TO safeai_chat_erasure_worker;
GRANT UPDATE (content) ON public.chat_messages TO safeai_chat_erasure_worker;
GRANT UPDATE (query_text) ON public.knowledge_retrieval_runs TO safeai_chat_erasure_worker;
GRANT INSERT ON public.audit_outbox TO safeai_chat_erasure_worker;

-- Remove direct access to operator functions from app/worker even if inherited.
REVOKE ALL ON FUNCTION public.chat_operator_approve_v86(text,uuid,text) FROM safeai_app,safeai_chat_erasure_worker;
REVOKE ALL ON FUNCTION public.chat_operator_release_hold_v86(uuid,text) FROM safeai_app, safeai_chat_erasure_worker;
REVOKE ALL ON FUNCTION public.chat_operator_resolve_erasure_v86(uuid,text,text) FROM safeai_app, safeai_chat_erasure_worker;

-- GRANT/REVOKE cannot remove privileges inherited through unrelated roles or
-- database-wide default grants. Fail the migration rather than deploying an
-- apparent separation that is not enforced by PostgreSQL.
DO
$privilege_gate$
    BEGIN
        IF has_table_privilege('safeai_app', 'public.chat_erasure_operations_v86', 'UPDATE')
            OR has_table_privilege('safeai_app', 'public.chat_erasure_operations_v86', 'DELETE')
            OR has_table_privilege('safeai_app', 'public.chat_retention_holds_v86', 'UPDATE')
            OR has_table_privilege('safeai_app', 'public.chat_retention_holds_v86', 'DELETE')
            OR has_table_privilege('safeai_app', 'public.chat_erasure_operator_approvals_v86', 'INSERT')
            OR has_table_privilege('safeai_chat_erasure_worker', 'public.chat_retention_holds_v86', 'UPDATE')
            OR has_table_privilege('safeai_chat_erasure_worker', 'public.chat_erasure_operator_approvals_v86', 'INSERT')
            OR pg_has_role('safeai_chat_erasure_worker', 'safeai_app', 'member') THEN
            RAISE EXCEPTION 'V86 erasure role privilege isolation failed';
        END IF;
    END;
$privilege_gate$;
