-- SafeAI-Desk/backend/src/main/resources/db/migration/V106__chat_execution_permit_ledger.sql
-- R3a.1c: durable NON-EXECUTABLE ledger and cutover fence.
-- Deploy only after isolated clean/upgrade acceptance; V1..V105 unchanged.
-- R3a.1: one-way cutover fence; does not issue or consume an executable permit.
-- Existing V86 behaviour is unchanged until an explicitly approved seal.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = pg_catalog, pg_temp;

DO $preflight$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version='105' AND success)
       OR session_user IN ('safeai_app','safeai_chat_erasure_worker','safeai_knowledge_erasure_worker')
       OR pg_has_role('safeai_app','safeai_chat_erasure_operator','member')
       OR pg_has_role('safeai_app','safeai_chat_erasure_worker','member')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='public.chat_sessions'::regclass
          AND tgname='trg_chat_session_erasure_v86' AND tgenabled IN ('O','A') AND NOT tgisinternal)
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='public.chat_erasure_operations_v86'::regclass
          AND tgname='trg_chat_erasure_op_guard_v86' AND tgenabled IN ('O','A') AND NOT tgisinternal)
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='public.chat_erasure_operations_v86'::regclass
          AND tgname='trg_chat_erasure_intent_insert_v86' AND tgenabled IN ('O','A') AND NOT tgisinternal)
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='public.chat_messages'::regclass
          AND tgname='trg_chat_erasure_message_guard_v86' AND tgenabled IN ('O','A') AND NOT tgisinternal)
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='public.knowledge_retrieval_runs'::regclass
          AND tgname='trg_knowledge_retrieval_runs_immutable_v44' AND tgenabled IN ('O','A') AND NOT tgisinternal) THEN
        RAISE EXCEPTION 'V106 requires reviewed V105, isolated roles and V86 guards';
    END IF;
END;
$preflight$;

CREATE TABLE public.chat_execution_cutover_v106 (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton IS TRUE),
    mode text NOT NULL CHECK (mode IN ('LEGACY_COMPATIBILITY','SEALED')),
    sealed_at timestamptz,
    CHECK ((mode = 'SEALED') = (sealed_at IS NOT NULL))
);
INSERT INTO public.chat_execution_cutover_v106(singleton,mode)
VALUES (true,'LEGACY_COMPATIBILITY');
REVOKE ALL ON public.chat_execution_cutover_v106 FROM PUBLIC,
    safeai_app, safeai_chat_erasure_worker, safeai_knowledge_erasure_worker,
    safeai_chat_erasure_operator, safeai_knowledge_erasure_operator;

-- The owner is the only intended writer. Runtime roles never obtain table
-- rights. This is a one-way transition; no runtime-controlled GUC or flag.
CREATE FUNCTION public.guard_chat_execution_cutover_v106()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='CHAT_CUTOVER_IMMUTABLE';
    END IF;
    IF OLD.mode IS DISTINCT FROM 'LEGACY_COMPATIBILITY'
       OR NEW.mode IS DISTINCT FROM 'SEALED'
       OR NEW.singleton IS DISTINCT FROM OLD.singleton
       OR NEW.sealed_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='CHAT_CUTOVER_INVALID_TRANSITION';
    END IF;
    -- A stale REPEATABLE READ snapshot cannot certify a drained worker.
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='CHAT_CUTOVER_REQUIRES_READ_COMMITTED';
    END IF;
    -- NOWAIT prevents lock inversion with V86 (chat/session -> operation).
    -- Any concurrent writer, including a late V86 intent, blocks cutover.
    LOCK TABLE public.chat_sessions, public.chat_erasure_operations_v86,
        public.chat_messages, public.knowledge_retrieval_runs
        IN SHARE ROW EXCLUSIVE MODE NOWAIT;
    IF EXISTS (SELECT 1 FROM public.chat_erasure_operations_v86
               WHERE state NOT IN ('ERASED','CANCELLED') OR lease_token IS NOT NULL OR lease_until IS NOT NULL)
       OR EXISTS (SELECT 1 FROM public.chat_sessions WHERE erasure_state='ERASING') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='CHAT_CUTOVER_REQUIRES_DRAINED_LEGACY';
    END IF;
    NEW.sealed_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_chat_execution_cutover_guard_v106
BEFORE INSERT OR UPDATE OR DELETE ON public.chat_execution_cutover_v106
FOR EACH ROW EXECUTE FUNCTION public.guard_chat_execution_cutover_v106();
CREATE TRIGGER trg_chat_execution_cutover_no_truncate_v106
BEFORE TRUNCATE ON public.chat_execution_cutover_v106
FOR EACH STATEMENT EXECUTE FUNCTION public.guard_chat_execution_cutover_v106();
REVOKE ALL ON FUNCTION public.guard_chat_execution_cutover_v106() FROM PUBLIC,
    safeai_app, safeai_chat_erasure_worker, safeai_knowledge_erasure_worker,
    safeai_chat_erasure_operator, safeai_knowledge_erasure_operator;

-- Private definer only because runtime logins must not receive access to the
-- gate table. No dynamic SQL, fixed search_path, no caller-supplied authority.
-- In compatibility mode existing V86 guards continue to be mandatory.
-- In sealed mode all new V86 intents, leases, operator resumes, and state
-- transitions are blocked. No exception for APPROVED_REVIEW_ONLY, a GUC,
-- raw SQL, worker identity, or operator role. The next reviewed migration
-- must supply the real permit issuance/consumption/barrier contract.
CREATE FUNCTION public.guard_chat_execution_intent_v106()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp AS $$
DECLARE gate_mode text;
BEGIN
    SELECT mode INTO gate_mode FROM public.chat_execution_cutover_v106
    WHERE singleton IS TRUE FOR SHARE;
    IF gate_mode IS DISTINCT FROM 'LEGACY_COMPATIBILITY' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='CHAT_EXECUTION_GATE_SEALED';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_chat_admission_gate_v106
BEFORE INSERT OR UPDATE ON public.chat_erasure_operations_v86
FOR EACH ROW EXECUTE FUNCTION public.guard_chat_execution_intent_v106();
REVOKE ALL ON FUNCTION public.guard_chat_execution_intent_v106() FROM PUBLIC,
    safeai_app, safeai_chat_erasure_worker, safeai_knowledge_erasure_worker,
    safeai_chat_erasure_operator, safeai_knowledge_erasure_operator;

-- A second fence on direct session transitions. V86 continues to protect
-- messages and retrievals via its live operation + lease checks. No ERASING
-- session or live operation is permitted to survive the seal transition.
CREATE TRIGGER trg_chat_execution_session_gate_v106
BEFORE UPDATE OF erasure_state ON public.chat_sessions
FOR EACH ROW WHEN (OLD.erasure_state IS DISTINCT FROM NEW.erasure_state)
EXECUTE FUNCTION public.guard_chat_execution_intent_v106();

-- Additional independent fence for direct worker writes. Ordinary app chat
-- writes do not pass through these worker-only triggers; V86 still applies.
CREATE TRIGGER trg_chat_execution_worker_session_v106
BEFORE UPDATE ON public.chat_sessions
FOR EACH ROW WHEN (session_user = 'safeai_chat_erasure_worker')
EXECUTE FUNCTION public.guard_chat_execution_intent_v106();
CREATE TRIGGER trg_chat_execution_worker_message_v106
BEFORE UPDATE OF content ON public.chat_messages
FOR EACH ROW WHEN (session_user = 'safeai_chat_erasure_worker')
EXECUTE FUNCTION public.guard_chat_execution_intent_v106();
CREATE TRIGGER trg_chat_execution_worker_retrieval_v106
BEFORE UPDATE OF query_text ON public.knowledge_retrieval_runs
FOR EACH ROW WHEN (session_user = 'safeai_chat_erasure_worker')
EXECUTE FUNCTION public.guard_chat_execution_intent_v106();

-- Gate checks must not disappear in replication mode. The DBA/migrator who can
-- alter DDL is explicitly outside the runtime attacker boundary.
ALTER TABLE public.chat_execution_cutover_v106 ENABLE ALWAYS TRIGGER trg_chat_execution_cutover_guard_v106;
ALTER TABLE public.chat_execution_cutover_v106 ENABLE ALWAYS TRIGGER trg_chat_execution_cutover_no_truncate_v106;
ALTER TABLE public.chat_erasure_operations_v86 ENABLE ALWAYS TRIGGER trg_chat_admission_gate_v106;
ALTER TABLE public.chat_sessions ENABLE ALWAYS TRIGGER trg_chat_execution_session_gate_v106;
ALTER TABLE public.chat_sessions ENABLE ALWAYS TRIGGER trg_chat_execution_worker_session_v106;
ALTER TABLE public.chat_messages ENABLE ALWAYS TRIGGER trg_chat_execution_worker_message_v106;
ALTER TABLE public.knowledge_retrieval_runs ENABLE ALWAYS TRIGGER trg_chat_execution_worker_retrieval_v106;

-- Conservative platform-wide epochs. These are invalidation counters, not a
-- permission grant. An unrelated authority/hold mutation may invalidate a
-- candidate. No per-resource counter may be reset by deleting a live actor.
CREATE TABLE public.erasure_execution_epochs_v106 (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton IS TRUE),
    authority_epoch bigint NOT NULL DEFAULT 0 CHECK (authority_epoch >= 0),
    all_holds_epoch bigint NOT NULL DEFAULT 0 CHECK (all_holds_epoch >= 0)
);
INSERT INTO public.erasure_execution_epochs_v106 DEFAULT VALUES;
CREATE FUNCTION public.guard_erasure_epochs_v106() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_EPOCHS_IMMUTABLE';
    END IF;
    IF NEW.singleton IS DISTINCT FROM OLD.singleton OR NOT (
        (NEW.authority_epoch = OLD.authority_epoch + 1 AND NEW.all_holds_epoch = OLD.all_holds_epoch)
        OR (NEW.all_holds_epoch = OLD.all_holds_epoch + 1 AND NEW.authority_epoch = OLD.authority_epoch)
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_EPOCHS_MONOTONIC';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_erasure_epochs_guard_v106 BEFORE INSERT OR UPDATE OR DELETE
ON public.erasure_execution_epochs_v106 FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_epochs_truncate_v106 BEFORE TRUNCATE
ON public.erasure_execution_epochs_v106 FOR EACH STATEMENT EXECUTE FUNCTION public.guard_erasure_epochs_v106();

-- Private definer; only installed triggers can call it. Statement-level bumps
-- deliberately include no-op writes and TRUNCATE (conservative invalidation).
-- Auth/hold mutations and their epoch bump commit or roll back together.
CREATE FUNCTION public.bump_erasure_epochs_v106() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_TABLE_SCHEMA <> 'public' OR TG_NARGS <> 0 THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_EPOCH_TRIGGER_INVALID';
    END IF;
    IF TG_TABLE_NAME IN ('erasure_review_authorities_v105','erasure_review_actor_keys_v105',
                        'user_roles','users','organizations','roles','platform_erasure_policy_v102') THEN
        UPDATE public.erasure_execution_epochs_v106 SET authority_epoch=authority_epoch+1 WHERE singleton;
    ELSIF TG_TABLE_NAME IN ('chat_retention_holds_v86','erasure_review_holds_v105') THEN
        UPDATE public.erasure_execution_epochs_v106 SET all_holds_epoch=all_holds_epoch+1 WHERE singleton;
    ELSE
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_EPOCH_TRIGGER_INVALID';
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_EPOCHS_MISSING';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_erasure_authority_epoch_v106 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON public.erasure_review_authorities_v105 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_review_key_epoch_v106 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON public.erasure_review_actor_keys_v105 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_role_epoch_v106 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON public.user_roles FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_role_catalog_epoch_v106 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON public.roles FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_user_epoch_v106 AFTER INSERT OR DELETE OR TRUNCATE
ON public.users FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_user_auth_epoch_v106 AFTER UPDATE OF enabled,token_version,organization_id,password_hash
ON public.users FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_org_epoch_v106 AFTER INSERT OR DELETE OR TRUNCATE
ON public.organizations FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_org_auth_epoch_v106 AFTER UPDATE OF enabled,auth_version
ON public.organizations FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_policy_epoch_v106 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON public.platform_erasure_policy_v102 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_v86_hold_epoch_v106 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON public.chat_retention_holds_v86 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();
CREATE TRIGGER trg_erasure_v105_hold_epoch_v106 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON public.erasure_review_holds_v105 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_erasure_epochs_v106();

-- Historical binding metadata. No live FK from permit to user/chat/request:
-- preserving evidence must not make future authorized account deletion impossible.
-- Rows are *recorded candidates*, not verified identities or issued permits.
-- No executable/consumed state exists in V106, and no runtime writer is granted.
CREATE TABLE public.erasure_execution_permits_v106 (
    permit_id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    chat_id uuid NOT NULL,
    reviewer_id uuid NOT NULL,
    execution_approver_id uuid NOT NULL,
    case_version bigint NOT NULL CHECK (case_version >= 0),
    resource_version bigint NOT NULL CHECK (resource_version >= 0),
    review_version bigint NOT NULL CHECK (review_version >= 0),
    owner_token_version bigint NOT NULL CHECK (owner_token_version >= 0),
    reviewer_token_version bigint NOT NULL CHECK (reviewer_token_version >= 0),
    approver_token_version bigint NOT NULL CHECK (approver_token_version >= 0),
    organization_auth_version bigint NOT NULL CHECK (organization_auth_version >= 0),
    policy_version bigint NOT NULL CHECK (policy_version >= 0),
    authority_epoch bigint NOT NULL CHECK (authority_epoch >= 0),
    all_holds_epoch bigint NOT NULL CHECK (all_holds_epoch >= 0),
    intake_digest text NOT NULL CHECK (intake_digest ~ '^[0-9a-f]{64}$'),
    manifest_digest text NOT NULL CHECK (manifest_digest ~ '^[0-9a-f]{64}$'),
    evidence_digest text NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    identity_proof_ref uuid NOT NULL,
    manifest_ref uuid NOT NULL,
    decision_ref uuid NOT NULL,
    identity_nonce uuid NOT NULL,
    manifest_nonce uuid NOT NULL,
    decision_nonce uuid NOT NULL,
    permit_nonce uuid NOT NULL,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    state text NOT NULL DEFAULT 'RECORDED_ONLY' CHECK (state IN ('RECORDED_ONLY','REVOKED')),
    row_version bigint NOT NULL DEFAULT 0 CHECK (row_version IN (0,1)),
    revoked_at timestamptz,
    CHECK (owner_id <> reviewer_id AND owner_id <> execution_approver_id AND reviewer_id <> execution_approver_id),
    CHECK (isfinite(issued_at) AND isfinite(expires_at) AND expires_at > issued_at
        AND expires_at <= issued_at + interval '5 minutes'),
    CHECK ((state='RECORDED_ONLY' AND row_version=0 AND revoked_at IS NULL)
        OR (state='REVOKED' AND row_version=1 AND revoked_at IS NOT NULL AND revoked_at>=recorded_at)),
    CHECK (NOT ('00000000-0000-0000-0000-000000000000'::uuid = ANY(ARRAY[
        permit_id,request_id,organization_id,owner_id,chat_id,reviewer_id,execution_approver_id,
        identity_proof_ref,manifest_ref,decision_ref,identity_nonce,manifest_nonce,decision_nonce,permit_nonce])))
);
CREATE INDEX ix_execution_permit_request_v106 ON public.erasure_execution_permits_v106(organization_id,request_id,recorded_at,permit_id);

-- One identifier namespace for permit IDs, proof references AND nonces, across tenants
-- and purposes. A duplicate rolls back the entire insert (no partial reserve).
-- Revocation and expiry NEVER free a claimed identifier.
CREATE TABLE public.erasure_execution_identifiers_v106 (
    identifier uuid PRIMARY KEY CHECK (identifier <> '00000000-0000-0000-0000-000000000000'::uuid),
    permit_id uuid NOT NULL REFERENCES public.erasure_execution_permits_v106(permit_id) ON DELETE RESTRICT,
    purpose text NOT NULL CHECK (purpose IN ('PERMIT_ID','IDENTITY','MANIFEST','INDEPENDENT_DECISION','PERMIT','IDENTITY_REF','MANIFEST_REF','DECISION_REF')),
    UNIQUE (permit_id,purpose)
);

CREATE FUNCTION public.guard_erasure_permit_v106() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE current_authority bigint; current_holds bigint; observed_at timestamptz;
BEGIN
    IF TG_OP='INSERT' THEN
        observed_at := clock_timestamp();
        IF NEW.state IS DISTINCT FROM 'RECORDED_ONLY' OR NEW.row_version IS DISTINCT FROM 0
           OR NEW.revoked_at IS NOT NULL OR NEW.issued_at>observed_at OR NEW.expires_at<=observed_at THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_PERMIT_RECORD_ONLY';
        END IF;
        SELECT authority_epoch,all_holds_epoch INTO current_authority,current_holds
            FROM public.erasure_execution_epochs_v106 WHERE singleton FOR SHARE;
        IF NOT FOUND OR NEW.authority_epoch IS DISTINCT FROM current_authority
            OR NEW.all_holds_epoch IS DISTINCT FROM current_holds THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_PERMIT_STALE_EPOCH';
        END IF;
        -- Candidate records are allowed only in a drained, sealed environment.
        PERFORM 1 FROM public.chat_execution_cutover_v106 WHERE singleton AND mode='SEALED' FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_PERMIT_REQUIRES_SEAL';
        END IF;
        NEW.recorded_at := observed_at;
        RETURN NEW;
    ELSIF TG_OP='UPDATE' THEN
        -- Only irreversible revocation. Input may change state alone; the DB
        -- assigns the timestamp/version. All other fields remain immutable.
        IF OLD.state IS DISTINCT FROM 'RECORDED_ONLY' OR NEW.state IS DISTINCT FROM 'REVOKED' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_PERMIT_IMMUTABLE';
        END IF;
        NEW.state := OLD.state;
        IF NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_PERMIT_IMMUTABLE';
        END IF;
        NEW.state := 'REVOKED';
        NEW.row_version := 1;
        NEW.revoked_at := greatest(clock_timestamp(),OLD.recorded_at);
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_PERMIT_IMMUTABLE';
END;
$$;
CREATE FUNCTION public.reserve_erasure_identifiers_v106() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    -- Deterministic lock acquisition order across concurrent overlapping sets.
    INSERT INTO public.erasure_execution_identifiers_v106(identifier,permit_id,purpose)
    SELECT v.identifier,NEW.permit_id,v.purpose
    FROM (VALUES (NEW.permit_id,'PERMIT_ID'),(NEW.identity_nonce,'IDENTITY'),
         (NEW.manifest_nonce,'MANIFEST'),(NEW.decision_nonce,'INDEPENDENT_DECISION'),
         (NEW.permit_nonce,'PERMIT'),(NEW.identity_proof_ref,'IDENTITY_REF'),
         (NEW.manifest_ref,'MANIFEST_REF'),(NEW.decision_ref,'DECISION_REF')) AS v(identifier,purpose)
    ORDER BY v.identifier;
    RETURN NULL;
END;
$$;
CREATE FUNCTION public.guard_erasure_identifiers_v106() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_IDENTIFIER_IMMUTABLE';
    END IF;
    IF pg_trigger_depth()>1 AND EXISTS (
        SELECT 1 FROM public.erasure_execution_permits_v106 p WHERE p.permit_id=NEW.permit_id
        AND NEW.identifier=CASE NEW.purpose WHEN 'PERMIT_ID' THEN p.permit_id
            WHEN 'IDENTITY' THEN p.identity_nonce WHEN 'MANIFEST' THEN p.manifest_nonce
            WHEN 'INDEPENDENT_DECISION' THEN p.decision_nonce WHEN 'PERMIT' THEN p.permit_nonce
            WHEN 'IDENTITY_REF' THEN p.identity_proof_ref WHEN 'MANIFEST_REF' THEN p.manifest_ref
            WHEN 'DECISION_REF' THEN p.decision_ref END
    ) THEN RETURN NEW; END IF;
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_IDENTIFIER_IMMUTABLE';
END;
$$;
CREATE TRIGGER trg_erasure_permit_guard_v106 BEFORE INSERT OR UPDATE OR DELETE
ON public.erasure_execution_permits_v106 FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_permit_v106();
CREATE TRIGGER trg_erasure_permit_truncate_v106 BEFORE TRUNCATE
ON public.erasure_execution_permits_v106 FOR EACH STATEMENT EXECUTE FUNCTION public.guard_erasure_permit_v106();
CREATE TRIGGER trg_erasure_permit_reserve_v106 AFTER INSERT
ON public.erasure_execution_permits_v106 FOR EACH ROW EXECUTE FUNCTION public.reserve_erasure_identifiers_v106();
CREATE TRIGGER trg_erasure_identifier_guard_v106 BEFORE INSERT OR UPDATE OR DELETE
ON public.erasure_execution_identifiers_v106 FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_identifiers_v106();
CREATE TRIGGER trg_erasure_identifier_truncate_v106 BEFORE TRUNCATE
ON public.erasure_execution_identifiers_v106 FOR EACH STATEMENT EXECUTE FUNCTION public.guard_erasure_identifiers_v106();

REVOKE ALL ON public.erasure_execution_epochs_v106,public.erasure_execution_permits_v106,
    public.erasure_execution_identifiers_v106 FROM PUBLIC,safeai_app,safeai_chat_erasure_worker,
    safeai_knowledge_erasure_worker,safeai_chat_erasure_operator,safeai_knowledge_erasure_operator;
REVOKE ALL ON FUNCTION public.guard_erasure_epochs_v106(),public.bump_erasure_epochs_v106(),
    public.guard_erasure_permit_v106(),public.reserve_erasure_identifiers_v106(),
    public.guard_erasure_identifiers_v106() FROM PUBLIC,safeai_app,safeai_chat_erasure_worker,
    safeai_knowledge_erasure_worker,safeai_chat_erasure_operator,safeai_knowledge_erasure_operator;

-- All added triggers also fire in replica mode. Do not modify historic triggers.
ALTER TABLE public.erasure_execution_epochs_v106 ENABLE ALWAYS TRIGGER trg_erasure_epochs_guard_v106;
ALTER TABLE public.erasure_execution_epochs_v106 ENABLE ALWAYS TRIGGER trg_erasure_epochs_truncate_v106;
ALTER TABLE public.erasure_review_authorities_v105 ENABLE ALWAYS TRIGGER trg_erasure_authority_epoch_v106;
ALTER TABLE public.erasure_review_actor_keys_v105 ENABLE ALWAYS TRIGGER trg_erasure_review_key_epoch_v106;
ALTER TABLE public.user_roles ENABLE ALWAYS TRIGGER trg_erasure_role_epoch_v106;
ALTER TABLE public.roles ENABLE ALWAYS TRIGGER trg_erasure_role_catalog_epoch_v106;
ALTER TABLE public.users ENABLE ALWAYS TRIGGER trg_erasure_user_epoch_v106;
ALTER TABLE public.users ENABLE ALWAYS TRIGGER trg_erasure_user_auth_epoch_v106;
ALTER TABLE public.organizations ENABLE ALWAYS TRIGGER trg_erasure_org_epoch_v106;
ALTER TABLE public.organizations ENABLE ALWAYS TRIGGER trg_erasure_org_auth_epoch_v106;
ALTER TABLE public.platform_erasure_policy_v102 ENABLE ALWAYS TRIGGER trg_erasure_policy_epoch_v106;
ALTER TABLE public.chat_retention_holds_v86 ENABLE ALWAYS TRIGGER trg_erasure_v86_hold_epoch_v106;
ALTER TABLE public.erasure_review_holds_v105 ENABLE ALWAYS TRIGGER trg_erasure_v105_hold_epoch_v106;
ALTER TABLE public.erasure_execution_permits_v106 ENABLE ALWAYS TRIGGER trg_erasure_permit_guard_v106;
ALTER TABLE public.erasure_execution_permits_v106 ENABLE ALWAYS TRIGGER trg_erasure_permit_truncate_v106;
ALTER TABLE public.erasure_execution_permits_v106 ENABLE ALWAYS TRIGGER trg_erasure_permit_reserve_v106;
ALTER TABLE public.erasure_execution_identifiers_v106 ENABLE ALWAYS TRIGGER trg_erasure_identifier_guard_v106;
ALTER TABLE public.erasure_execution_identifiers_v106 ENABLE ALWAYS TRIGGER trg_erasure_identifier_truncate_v106;

-- V106 exact private ACL and trigger inventory (also reapplied by runtime ACL).
DO $v106_acl$
DECLARE r record; t record; f record;
BEGIN
    FOR r IN SELECT name FROM (VALUES ('safeai_app'),('safeai_chat_erasure_worker'),
        ('safeai_knowledge_erasure_worker'),('safeai_chat_erasure_operator'),
        ('safeai_knowledge_erasure_operator')) roles(name) LOOP
        IF pg_has_role(r.name,current_user,'MEMBER') OR has_schema_privilege(r.name,'public','CREATE')
            OR EXISTS (SELECT 1 FROM pg_roles pr WHERE pr.rolname=r.name
                AND (pr.rolsuper OR pr.rolbypassrls OR pr.rolcreaterole OR pr.rolcreatedb OR pr.rolreplication)) THEN
            RAISE EXCEPTION 'V106 runtime role inherits schema owner: %',r.name;
        END IF;
        FOR t IN SELECT name FROM (VALUES ('chat_execution_cutover_v106'),('erasure_execution_epochs_v106'),('erasure_execution_permits_v106'),('erasure_execution_identifiers_v106')) names(name) LOOP
            IF has_table_privilege(r.name,format('public.%I',t.name),
                'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                OR has_any_column_privilege(r.name,format('public.%I',t.name),'SELECT,INSERT,UPDATE,REFERENCES') THEN
                RAISE EXCEPTION 'V106 private table privilege: % %',r.name,t.name;
            END IF;
        END LOOP;
        FOR f IN SELECT name FROM (VALUES ('guard_chat_execution_cutover_v106'),('guard_chat_execution_intent_v106'),('guard_erasure_epochs_v106'),('bump_erasure_epochs_v106'),('guard_erasure_permit_v106'),('reserve_erasure_identifiers_v106'),('guard_erasure_identifiers_v106')) names(name) LOOP
            IF has_function_privilege(r.name,format('public.%I()',f.name),'EXECUTE') THEN
                RAISE EXCEPTION 'V106 private function privilege: % %',r.name,f.name;
            END IF;
        END LOOP;
    END LOOP;
    FOR t IN SELECT name FROM (VALUES ('chat_execution_cutover_v106'),('erasure_execution_epochs_v106'),('erasure_execution_permits_v106'),('erasure_execution_identifiers_v106')) names(name) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid=format('public.%I',t.name)::regclass
            AND c.relowner=current_user::regrole AND c.relkind='r') OR EXISTS (
            SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
            WHERE c.oid=format('public.%I',t.name)::regclass AND a.grantee=0
        ) OR EXISTS (SELECT 1 FROM pg_attribute c CROSS JOIN LATERAL aclexplode(c.attacl) a
            WHERE c.attrelid=format('public.%I',t.name)::regclass AND a.grantee=0) THEN
            RAISE EXCEPTION 'V106 private table owner/PUBLIC ACL: %',t.name;
        END IF;
    END LOOP;
    FOR f IN SELECT name,definer FROM (VALUES ('guard_chat_execution_cutover_v106',false),('guard_chat_execution_intent_v106',true),('guard_erasure_epochs_v106',false),('bump_erasure_epochs_v106',true),('guard_erasure_permit_v106',false),('reserve_erasure_identifiers_v106',false),('guard_erasure_identifiers_v106',false)) names(name,definer) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid=format('public.%I()',f.name)::regprocedure
            AND p.proowner=current_user::regrole AND p.prosecdef=f.definer
            AND p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp']) OR EXISTS (
            SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
            WHERE p.oid=format('public.%I()',f.name)::regprocedure AND a.grantee=0) THEN
            RAISE EXCEPTION 'V106 private function owner/PUBLIC ACL: %',f.name;
        END IF;
    END LOOP;
    FOR t IN SELECT trg,rel,fn FROM (VALUES
        ('trg_chat_execution_cutover_guard_v106','chat_execution_cutover_v106','guard_chat_execution_cutover_v106'),
        ('trg_chat_execution_cutover_no_truncate_v106','chat_execution_cutover_v106','guard_chat_execution_cutover_v106'),
        ('trg_chat_admission_gate_v106','chat_erasure_operations_v86','guard_chat_execution_intent_v106'),
        ('trg_chat_execution_session_gate_v106','chat_sessions','guard_chat_execution_intent_v106'),
        ('trg_chat_execution_worker_session_v106','chat_sessions','guard_chat_execution_intent_v106'),
        ('trg_chat_execution_worker_message_v106','chat_messages','guard_chat_execution_intent_v106'),
        ('trg_chat_execution_worker_retrieval_v106','knowledge_retrieval_runs','guard_chat_execution_intent_v106'),
        ('trg_erasure_epochs_guard_v106','erasure_execution_epochs_v106','guard_erasure_epochs_v106'),
        ('trg_erasure_epochs_truncate_v106','erasure_execution_epochs_v106','guard_erasure_epochs_v106'),
        ('trg_erasure_authority_epoch_v106','erasure_review_authorities_v105','bump_erasure_epochs_v106'),
        ('trg_erasure_review_key_epoch_v106','erasure_review_actor_keys_v105','bump_erasure_epochs_v106'),
        ('trg_erasure_role_epoch_v106','user_roles','bump_erasure_epochs_v106'),
        ('trg_erasure_role_catalog_epoch_v106','roles','bump_erasure_epochs_v106'),
        ('trg_erasure_user_epoch_v106','users','bump_erasure_epochs_v106'),
        ('trg_erasure_user_auth_epoch_v106','users','bump_erasure_epochs_v106'),
        ('trg_erasure_org_epoch_v106','organizations','bump_erasure_epochs_v106'),
        ('trg_erasure_org_auth_epoch_v106','organizations','bump_erasure_epochs_v106'),
        ('trg_erasure_policy_epoch_v106','platform_erasure_policy_v102','bump_erasure_epochs_v106'),
        ('trg_erasure_v86_hold_epoch_v106','chat_retention_holds_v86','bump_erasure_epochs_v106'),
        ('trg_erasure_v105_hold_epoch_v106','erasure_review_holds_v105','bump_erasure_epochs_v106'),
        ('trg_erasure_permit_guard_v106','erasure_execution_permits_v106','guard_erasure_permit_v106'),
        ('trg_erasure_permit_truncate_v106','erasure_execution_permits_v106','guard_erasure_permit_v106'),
        ('trg_erasure_permit_reserve_v106','erasure_execution_permits_v106','reserve_erasure_identifiers_v106'),
        ('trg_erasure_identifier_guard_v106','erasure_execution_identifiers_v106','guard_erasure_identifiers_v106'),
        ('trg_erasure_identifier_truncate_v106','erasure_execution_identifiers_v106','guard_erasure_identifiers_v106')) inventory(trg,rel,fn) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger g WHERE g.tgrelid=format('public.%I',t.rel)::regclass
            AND g.tgname=t.trg AND g.tgfoid=format('public.%I()',t.fn)::regprocedure
            AND NOT g.tgisinternal AND g.tgenabled='A') THEN
            RAISE EXCEPTION 'V106 trigger missing/disabled: %',t.trg;
        END IF;
    END LOOP;
END;
$v106_acl$;
