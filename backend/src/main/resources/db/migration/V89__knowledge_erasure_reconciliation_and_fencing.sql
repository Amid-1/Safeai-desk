-- SafeAI-Desk/backend/src/main/resources/db/migration/V89__knowledge_erasure_reconciliation_and_fencing.sql
-- V89: Knowledge erasure reconciliation, independent destructive worker identity,
-- and dual-control retention recovery. All V1-V85 checks remain in force.
-- Flyway executes this as one transaction. No content or Object Lock is removed by this migration.
DO $preflight$
BEGIN
 IF to_regclass('public.flyway_schema_history') IS NULL OR NOT EXISTS
   (SELECT 1 FROM public.flyway_schema_history WHERE version='88' AND success) THEN
   RAISE EXCEPTION 'V89 needs applied V88';
 END IF;
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='safeai_knowledge_erasure_worker'
       AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls)
    OR NOT EXISTS (SELECT FROM pg_roles WHERE rolname='safeai_knowledge_erasure_operator'
       AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls)
    OR NOT EXISTS (SELECT FROM pg_roles WHERE rolname='safeai_app' AND NOT rolsuper)
    OR pg_has_role('safeai_app','safeai_knowledge_erasure_worker','member')
    OR pg_has_role('safeai_app','safeai_knowledge_erasure_operator','member')
    OR pg_has_role('safeai_knowledge_erasure_worker','safeai_knowledge_erasure_operator','member') THEN
   RAISE EXCEPTION 'V89: isolated app / erasure worker / 2-person operator roles must be provisioned first';
 END IF;
 IF session_user IN ('safeai_app','safeai_knowledge_erasure_worker') THEN
   RAISE EXCEPTION 'V89 requires a distinct Flyway migrator';
 END IF;
END $preflight$;

-- Completion receipt is created in the SAME commit as PostgreSQL DELETED.
-- A separate publisher retries publishing to the immutable S3 journal.
CREATE TABLE public.knowledge_erasure_commit_outbox_v89 (
 kind varchar(8) NOT NULL CHECK (kind IN ('DOCUMENT','BASE')),
 operation_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 knowledge_base_id uuid NOT NULL,
 document_id uuid,
 requested_by_user_id uuid NOT NULL,
 expected_version bigint NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (state IN ('PENDING','DELIVERING','DELIVERED')),
 attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 lease_token uuid,
 lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 delivered_at timestamptz,
 last_error_code varchar(64),
 CONSTRAINT pk_knowledge_commit_receipt_v89 PRIMARY KEY(kind,operation_id),
 CONSTRAINT chk_knowledge_receipt_doc_v89 CHECK
   ((kind='DOCUMENT' AND document_id IS NOT NULL) OR (kind='BASE' AND document_id IS NULL)),
 CONSTRAINT chk_knowledge_receipt_lease_v89 CHECK ((lease_token IS NULL)=(lease_until IS NULL)),
 CONSTRAINT chk_knowledge_receipt_completed_v89 CHECK ((state='DELIVERED')=(delivered_at IS NOT NULL))
);
CREATE INDEX ix_knowledge_commit_receipt_pending_v89
 ON public.knowledge_erasure_commit_outbox_v89(next_attempt_at,kind,operation_id)
 WHERE state IN ('PENDING','DELIVERING');

CREATE FUNCTION public.enqueue_knowledge_commit_receipt_v89()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $fn$
DECLARE v_kind varchar(8); v_document uuid;
BEGIN
 IF OLD.state IS DISTINCT FROM 'DELETED' AND NEW.state='DELETED' THEN
   IF TG_TABLE_NAME='knowledge_document_deletions_v78' THEN
       v_kind:='DOCUMENT'; v_document:=NEW.document_id;
   ELSIF TG_TABLE_NAME='knowledge_base_deletions_v82' THEN
       v_kind:='BASE'; v_document:=NULL;
   ELSE
       RAISE EXCEPTION 'V89 unknown erasure operation source';
   END IF;
   INSERT INTO public.knowledge_erasure_commit_outbox_v89
     (kind,operation_id,organization_id,knowledge_base_id,document_id,
      requested_by_user_id,expected_version)
   VALUES(v_kind,NEW.id,NEW.organization_id,NEW.knowledge_base_id,v_document,
      NEW.requested_by_user_id,NEW.expected_version);
 END IF;
 RETURN NEW;
END $fn$;
CREATE TRIGGER trg_knowledge_document_commit_receipt_v89
 AFTER UPDATE OF state ON public.knowledge_document_deletions_v78
 FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state)
 EXECUTE FUNCTION public.enqueue_knowledge_commit_receipt_v89();
CREATE TRIGGER trg_knowledge_base_commit_receipt_v89
 AFTER UPDATE OF state ON public.knowledge_base_deletions_v82
 FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state)
 EXECUTE FUNCTION public.enqueue_knowledge_commit_receipt_v89();

-- Upgrade from already-completed (pre-V89) operations: publication is safely
-- idempotent because the external journal addresses receipts by operation UUID.
INSERT INTO public.knowledge_erasure_commit_outbox_v89
 (kind,operation_id,organization_id,knowledge_base_id,document_id,requested_by_user_id,expected_version)
SELECT 'DOCUMENT',id,organization_id,knowledge_base_id,document_id,requested_by_user_id,expected_version
 FROM public.knowledge_document_deletions_v78 WHERE state='DELETED';
INSERT INTO public.knowledge_erasure_commit_outbox_v89
 (kind,operation_id,organization_id,knowledge_base_id,document_id,requested_by_user_id,expected_version)
SELECT 'BASE',id,organization_id,knowledge_base_id,NULL,requested_by_user_id,expected_version
 FROM public.knowledge_base_deletions_v82 WHERE state='DELETED';

-- The isolated worker can INSERT a final receipt only through a genuine
-- terminal operation transition. The migrator owns pre-V89 backfill; no other
-- runtime login has INSERT permission on the receipt table.
CREATE FUNCTION public.guard_knowledge_commit_receipt_insert_v89()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $fn$
BEGIN
 IF session_user='safeai_knowledge_erasure_worker' AND NOT (
    (NEW.kind='DOCUMENT' AND EXISTS (
       SELECT 1 FROM public.knowledge_document_deletions_v78 d
       WHERE d.id=NEW.operation_id AND d.state='DELETED'
         AND d.organization_id=NEW.organization_id
         AND d.knowledge_base_id=NEW.knowledge_base_id
         AND d.document_id=NEW.document_id
         AND d.requested_by_user_id=NEW.requested_by_user_id
         AND d.expected_version=NEW.expected_version
    )) OR
    (NEW.kind='BASE' AND NEW.document_id IS NULL AND EXISTS (
       SELECT 1 FROM public.knowledge_base_deletions_v82 b
       WHERE b.id=NEW.operation_id AND b.state='DELETED'
         AND b.organization_id=NEW.organization_id
         AND b.knowledge_base_id=NEW.knowledge_base_id
         AND b.requested_by_user_id=NEW.requested_by_user_id
         AND b.expected_version=NEW.expected_version
    ))) THEN
   RAISE EXCEPTION USING ERRCODE='23514',
     MESSAGE='V89 receipt must reference a completed erasure operation';
 END IF;
 IF NEW.state<>'PENDING' OR NEW.attempts<>0
     OR NEW.lease_token IS NOT NULL OR NEW.delivered_at IS NOT NULL THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V89 receipts start as PENDING';
 END IF;
 RETURN NEW;
END $fn$;
CREATE TRIGGER trg_knowledge_commit_receipt_insert_guard_v89
 BEFORE INSERT ON public.knowledge_erasure_commit_outbox_v89
 FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_commit_receipt_insert_v89();

-- Outbox delivery fields can only be updated by the dedicated publisher.
CREATE FUNCTION public.guard_knowledge_commit_receipt_v89()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $fn$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'V89 receipts cannot be deleted'; END IF;
 IF session_user<>'safeai_knowledge_erasure_worker' THEN
   RAISE EXCEPTION 'V89 receipt delivery needs isolated worker';
 END IF;
 IF (to_jsonb(NEW)-'state'-'attempts'-'next_attempt_at'-'lease_token'-'lease_until'-'delivered_at'-'last_error_code')
   IS DISTINCT FROM
    (to_jsonb(OLD)-'state'-'attempts'-'next_attempt_at'-'lease_token'-'lease_until'-'delivered_at'-'last_error_code')
    OR OLD.state='DELIVERED'
    OR NEW.state NOT IN ('PENDING','DELIVERING','DELIVERED') THEN
   RAISE EXCEPTION 'V89 receipt identity/status cannot be rewritten';
 END IF;
 IF NEW.state='DELIVERED' AND (OLD.state<>'DELIVERING'
     OR OLD.lease_token IS NULL OR OLD.lease_until<=clock_timestamp()
     OR NEW.delivered_at IS NULL) THEN
   RAISE EXCEPTION 'V89 delivered receipt requires live claim';
 END IF;
 RETURN NEW;
END $fn$;
CREATE TRIGGER trg_knowledge_commit_receipt_guard_v89
 BEFORE UPDATE OR DELETE ON public.knowledge_erasure_commit_outbox_v89
 FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_commit_receipt_v89();

-- Original V81/V82 checks are kept; add a mandatory SQL-login fence so the
-- application JDBC account cannot invoke a destructive transaction by setting GUCs.
CREATE OR REPLACE FUNCTION public.is_knowledge_erasure_lease_owner_v81(p_document_id uuid)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE op_text text;
DECLARE token_text text;
BEGIN
    IF session_user <> 'safeai_knowledge_erasure_worker' THEN RETURN false; END IF;
    op_text := current_setting('safeai.knowledge.erasure_operation', true);
    token_text := current_setting('safeai.knowledge.erasure_token', true);
    IF op_text IS NULL OR token_text IS NULL
       OR op_text !~ '^[0-9a-fA-F-]{36}$'
       OR token_text !~ '^[0-9a-fA-F-]{36}$' THEN
       RETURN false;
    END IF;
    RETURN EXISTS (
        SELECT 1
        FROM public.knowledge_document_deletions_v78 op
        JOIN public.knowledge_documents d
          ON d.id=op.document_id AND d.knowledge_base_id=op.knowledge_base_id
         AND d.organization_id=op.organization_id
        WHERE op.id=op_text::uuid AND op.lease_token=token_text::uuid
          AND op.lease_until > clock_timestamp() AND op.state='DELETING'
          AND d.lifecycle_state='DELETING' AND NOT d.enabled
          AND d.id=p_document_id
    );
END $$;

CREATE OR REPLACE FUNCTION public.is_knowledge_base_erasure_owner_v82(p_base_id uuid)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE v_operation text; v_token text;
BEGIN
    IF session_user <> 'safeai_knowledge_erasure_worker' THEN RETURN false; END IF;
    v_operation := current_setting('safeai.knowledge.base_erasure_operation', true);
    v_token := current_setting('safeai.knowledge.base_erasure_token', true);
    IF v_operation IS NULL OR v_token IS NULL
       OR v_operation !~ '^[0-9a-fA-F-]{36}$' OR v_token !~ '^[0-9a-fA-F-]{36}$' THEN
        RETURN false;
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM public.knowledge_base_deletions_v82 op
        WHERE op.id = v_operation::uuid AND op.lease_token = v_token::uuid
          AND op.lease_until > clock_timestamp() AND op.state = 'DELETING'
          AND op.knowledge_base_id = p_base_id
    );
END $$;

-- Preserve ordinary CRUD while isolating irreversible DELETE privileges.
-- Verify the final effective grants, not merely GRANT statements.
GRANT USAGE ON SCHEMA public TO safeai_knowledge_erasure_worker;
GRANT SELECT ON public.knowledge_document_deletions_v78,
 public.knowledge_base_deletions_v82, public.knowledge_documents,
 public.knowledge_bases, public.knowledge_document_versions,
 public.knowledge_document_chunks, public.knowledge_ingestion_jobs,
 public.knowledge_storage_upload_intents, public.knowledge_document_erasure_objects_v81,
 public.knowledge_retrieval_hits, public.knowledge_chunk_identities_v80,
 public.knowledge_index_generations, public.knowledge_rag_execution_permits_v85,
 public.knowledge_rag_execution_attempt_leases_v85,
 public.knowledge_rag_execution_permit_sources_v85
 TO safeai_knowledge_erasure_worker;
-- PostgreSQL row-locking SELECT ... FOR SHARE / FOR UPDATE requires UPDATE privilege.
-- V82 guards MUST retain their row locks: do not convert them to plain SELECT.
-- A column-scoped privilege is sufficient, so the erasure worker cannot edit
-- title, visibility, enabled, or version on the knowledge base.
GRANT UPDATE (lifecycle_state) ON public.knowledge_bases
 TO safeai_knowledge_erasure_worker;
GRANT INSERT,SELECT,DELETE ON public.knowledge_document_erasure_objects_v81
 TO safeai_knowledge_erasure_worker;
GRANT UPDATE ON public.knowledge_document_deletions_v78,
 public.knowledge_base_deletions_v82, public.knowledge_documents,
 public.knowledge_retrieval_hits, public.knowledge_index_generations
 TO safeai_knowledge_erasure_worker;
GRANT DELETE ON public.knowledge_document_chunks, public.knowledge_ingestion_jobs,
 public.knowledge_document_versions, public.knowledge_documents, public.knowledge_bases
 TO safeai_knowledge_erasure_worker;
GRANT INSERT ON public.audit_outbox TO safeai_knowledge_erasure_worker;
GRANT SELECT,INSERT,UPDATE ON public.knowledge_erasure_commit_outbox_v89
 TO safeai_knowledge_erasure_worker;
REVOKE ALL ON public.knowledge_erasure_commit_outbox_v89 FROM PUBLIC,safeai_app;
-- Read-only reconciliation metrics and incident diagnostics for the app.
GRANT SELECT ON public.knowledge_erasure_commit_outbox_v89 TO safeai_app;
-- REVOKE cannot neutralize owner or inherited role privileges; fail if still unsafe.
-- Application GC must retain its strictly V57-guarded DELETE on old chunk
-- generations, so do NOT revoke knowledge_document_chunks from safeai_app.
REVOKE DELETE ON public.knowledge_ingestion_jobs,
 public.knowledge_document_versions,public.knowledge_documents,public.knowledge_bases,
 public.knowledge_document_erasure_objects_v81
 FROM safeai_app,PUBLIC;
DO $permissions$
DECLARE v_table text;
BEGIN
 FOREACH v_table IN ARRAY ARRAY[
   'knowledge_ingestion_jobs',
   'knowledge_document_versions','knowledge_documents','knowledge_bases',
   'knowledge_document_erasure_objects_v81'
 ] LOOP
  IF has_table_privilege('safeai_app',format('public.%I',v_table),'DELETE') THEN
    RAISE EXCEPTION 'V89: app still has effective destructive DELETE on %',v_table;
  END IF;
 END LOOP;
 IF has_table_privilege('safeai_app','public.knowledge_erasure_commit_outbox_v89','UPDATE') THEN
    RAISE EXCEPTION 'V89: app must never acknowledge external erasure receipts';
 END IF;
END $permissions$;

-- Fail migration rather than silently leave V82 row-locking triggers unusable.
-- Equally important: a worker must not acquire broad UPDATE privileges on bases.
DO $knowledge_base_row_lock_privileges$
BEGIN
 IF NOT has_table_privilege('safeai_knowledge_erasure_worker',
                           'public.knowledge_bases','SELECT')
    OR NOT has_column_privilege('safeai_knowledge_erasure_worker',
                                'public.knowledge_bases','lifecycle_state','UPDATE') THEN
    RAISE EXCEPTION 'V89: knowledge erasure worker cannot acquire V82 base row lock';
 END IF;
 IF has_column_privilege('safeai_knowledge_erasure_worker',
                         'public.knowledge_bases','name','UPDATE')
    OR has_column_privilege('safeai_knowledge_erasure_worker',
                            'public.knowledge_bases','enabled','UPDATE')
    OR has_column_privilege('safeai_knowledge_erasure_worker',
                            'public.knowledge_bases','visibility','UPDATE')
    OR has_column_privilege('safeai_knowledge_erasure_worker',
                            'public.knowledge_bases','version','UPDATE') THEN
    RAISE EXCEPTION 'V89: knowledge erasure worker must not have broad UPDATE privilege on bases';
 END IF;
END $knowledge_base_row_lock_privileges$;

-- BLOCKED_BY_RETENTION is never silently reset; immutable append-only approvals
-- from two DISTINCT human database LOGIN identities are required.
CREATE TABLE public.knowledge_retention_recovery_reviews_v89 (
 kind varchar(8) NOT NULL CHECK(kind IN ('DOCUMENT','BASE')),
 operation_id uuid NOT NULL,
 blocked_updated_at timestamptz NOT NULL,
 reviewer_db_login name NOT NULL,
 ticket_code varchar(120) NOT NULL CHECK(ticket_code ~ '^[A-Z0-9][A-Z0-9._/-]{7,119}$'),
 evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(kind,operation_id,blocked_updated_at,reviewer_db_login)
);
CREATE FUNCTION public.protect_knowledge_retention_reviews_v89()
RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
 RAISE EXCEPTION 'V89: retention review evidence is append-only';
END $fn$;
CREATE TRIGGER trg_knowledge_retention_reviews_immutable_v89
 BEFORE UPDATE OR DELETE ON public.knowledge_retention_recovery_reviews_v89
 FOR EACH ROW EXECUTE FUNCTION public.protect_knowledge_retention_reviews_v89();
REVOKE ALL ON public.knowledge_retention_recovery_reviews_v89 FROM PUBLIC,safeai_app,
 safeai_knowledge_erasure_worker,safeai_knowledge_erasure_operator;

-- V81 and V85 blocked-state protection remains. The ONLY permitted escape
-- is an individually authorized review-based state change with proof retained.
CREATE OR REPLACE FUNCTION public.guard_knowledge_delete_completion_v81()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $fn$
BEGIN
 IF OLD.state='BLOCKED_BY_RETENTION' AND NEW IS DISTINCT FROM OLD THEN
  IF NOT (
    session_user NOT IN ('safeai_app','safeai_knowledge_erasure_worker')
    AND pg_has_role(session_user,'safeai_knowledge_erasure_operator','member')
    AND NEW.state='DELETING' AND OLD.lease_token IS NULL AND OLD.lease_until IS NULL
    AND (to_jsonb(NEW)-'state'-'next_attempt_at'-'updated_at')
        IS NOT DISTINCT FROM (to_jsonb(OLD)-'state'-'next_attempt_at'-'updated_at')
    AND (SELECT count(DISTINCT reviewer_db_login)
         FROM public.knowledge_retention_recovery_reviews_v89 r
         WHERE r.kind='DOCUMENT' AND r.operation_id=OLD.id
           AND r.blocked_updated_at=OLD.updated_at
           AND r.created_at>clock_timestamp()-interval '30 minutes')>=2
  ) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='V89: blocked Knowledge document cannot be silently resumed';
  END IF;
 END IF;
 IF OLD.state <> 'DELETED' AND NEW.state='DELETED' THEN
   IF EXISTS(SELECT 1 FROM public.knowledge_documents WHERE id=NEW.document_id)
      OR EXISTS(SELECT 1 FROM public.knowledge_document_versions WHERE document_id=NEW.document_id)
      OR EXISTS(SELECT 1 FROM public.knowledge_ingestion_jobs WHERE document_id=NEW.document_id)
      OR EXISTS(SELECT 1 FROM public.knowledge_document_chunks WHERE document_id=NEW.document_id)
      OR EXISTS(SELECT 1 FROM public.knowledge_document_erasure_objects_v81 WHERE operation_id=NEW.id) THEN
     RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='V81: online content or purge inventory remains';
   END IF;
 END IF;
 RETURN NEW;
END $fn$;
CREATE OR REPLACE FUNCTION public.guard_knowledge_base_blocked_retention_v85()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $fn$
BEGIN
 IF OLD.state='BLOCKED_BY_RETENTION' AND NEW IS DISTINCT FROM OLD THEN
   IF NOT (
     session_user NOT IN ('safeai_app','safeai_knowledge_erasure_worker')
     AND pg_has_role(session_user,'safeai_knowledge_erasure_operator','member')
     AND NEW.state='DELETING' AND OLD.lease_token IS NULL AND OLD.lease_until IS NULL
     AND (to_jsonb(NEW)-'state'-'next_attempt_at'-'updated_at')
         IS NOT DISTINCT FROM (to_jsonb(OLD)-'state'-'next_attempt_at'-'updated_at')
     AND (SELECT count(DISTINCT reviewer_db_login)
        FROM public.knowledge_retention_recovery_reviews_v89 r
        WHERE r.kind='BASE' AND r.operation_id=OLD.id
          AND r.blocked_updated_at=OLD.updated_at
          AND r.created_at>clock_timestamp()-interval '30 minutes')>=2
   ) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='V89: blocked Knowledge base cannot be silently resumed';
   END IF;
 END IF;
 IF OLD.state <> 'DELETED' AND NEW.state='DELETED' THEN
  IF EXISTS(
      SELECT 1 FROM public.knowledge_rag_execution_attempt_leases_v85 l
      JOIN public.knowledge_rag_execution_permits_v85 p ON p.id=l.permit_id
      WHERE p.knowledge_base_id=NEW.knowledge_base_id
        AND p.organization_id=NEW.organization_id AND l.state='ACTIVE'
  ) THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V85: active Knowledge attempts prevent base deletion';
  END IF;
 END IF;
 RETURN NEW;
END $fn$;

INSERT INTO public.audit_event_types(name,description) VALUES
 ('KNOWLEDGE_RETENTION_RECOVERY_REVIEWED','Operator independently approved retention recovery review'),
 ('KNOWLEDGE_RETENTION_RECOVERY_RESUMED','Two human operator reviews permitted resuming a blocked erasure')
ON CONFLICT(name) DO NOTHING;

CREATE FUNCTION public.review_blocked_knowledge_erasure_v89(
 p_kind varchar,p_operation uuid,p_blocked_at timestamptz,
 p_ticket varchar,p_evidence_sha256 char(64))
RETURNS varchar LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $fn$
DECLARE v_state text; v_updated timestamptz; v_entity uuid; v_org uuid;
DECLARE v_count integer; v_refcount integer;
BEGIN
 -- An actual human LOGIN is required. PostgreSQL superusers can appear
 -- to satisfy pg_has_role() for every role; never treat them as reviewers.
 IF session_user IN ('safeai_app','safeai_knowledge_erasure_worker')
    OR EXISTS(SELECT 1 FROM pg_catalog.pg_roles role
              WHERE role.rolname=session_user
                AND (role.rolsuper OR role.rolbypassrls
                     OR role.rolcreaterole OR NOT role.rolcanlogin))
    OR NOT pg_has_role(session_user,'safeai_knowledge_erasure_operator','member') THEN
   RAISE EXCEPTION 'V89: authenticated human operator role required';
 END IF;
 IF p_kind NOT IN ('DOCUMENT','BASE') OR p_operation IS NULL
    OR p_ticket !~ '^[A-Z0-9][A-Z0-9._/-]{7,119}$'
    OR p_evidence_sha256 !~ '^[0-9a-f]{64}$' THEN
   RAISE EXCEPTION 'V89: invalid recovery request';
 END IF;
 IF p_kind='DOCUMENT' THEN
   SELECT document_id,organization_id INTO v_entity,v_org
     FROM public.knowledge_document_deletions_v78 WHERE id=p_operation;
   IF v_entity IS NULL THEN RAISE EXCEPTION 'V89 document operation not found'; END IF;
   PERFORM 1 FROM public.knowledge_documents WHERE id=v_entity AND organization_id=v_org
        AND lifecycle_state='DELETING' AND NOT enabled FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'V89 document erasure is not fenced'; END IF;
   SELECT state,updated_at INTO v_state,v_updated FROM public.knowledge_document_deletions_v78
       WHERE id=p_operation FOR UPDATE;
 ELSE
   SELECT knowledge_base_id,organization_id INTO v_entity,v_org
     FROM public.knowledge_base_deletions_v82 WHERE id=p_operation;
   IF v_entity IS NULL THEN RAISE EXCEPTION 'V89 base operation not found'; END IF;
   PERFORM 1 FROM public.knowledge_bases WHERE id=v_entity AND organization_id=v_org
      AND lifecycle_state='DELETING' AND NOT enabled FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'V89 base erasure is not fenced'; END IF;
   SELECT state,updated_at INTO v_state,v_updated FROM public.knowledge_base_deletions_v82
      WHERE id=p_operation FOR UPDATE;
   IF EXISTS(SELECT 1 FROM public.knowledge_document_deletions_v78
         WHERE knowledge_base_id=v_entity AND organization_id=v_org
         AND state IN ('BLOCKED_BY_RETENTION','NEEDS_REVIEW','DELETING')) THEN
     RAISE EXCEPTION 'V89: review children before resuming base';
   END IF;
 END IF;
 IF v_state<>'BLOCKED_BY_RETENTION' OR v_updated IS DISTINCT FROM p_blocked_at THEN
    RAISE EXCEPTION 'V89: blocked-state version changed; re-review required';
 END IF;
 -- Subsequent approvals must agree on the same evidence and incident ticket.
 IF EXISTS(SELECT 1 FROM public.knowledge_retention_recovery_reviews_v89
       WHERE kind=p_kind AND operation_id=p_operation AND blocked_updated_at=p_blocked_at
         AND (ticket_code<>p_ticket OR evidence_sha256<>p_evidence_sha256)) THEN
    RAISE EXCEPTION 'V89: contradictory approvals; investigate without erasure';
 END IF;
 INSERT INTO public.knowledge_retention_recovery_reviews_v89
   (kind,operation_id,blocked_updated_at,reviewer_db_login,ticket_code,evidence_sha256)
 VALUES(p_kind,p_operation,p_blocked_at,session_user,p_ticket,p_evidence_sha256)
 ON CONFLICT DO NOTHING;
 INSERT INTO public.audit_outbox
 (id,actor_user_id,organization_id,event_type,details,occurred_at,created_at,attempt_count,next_attempt_at)
 VALUES(gen_random_uuid(),NULL,v_org,'KNOWLEDGE_RETENTION_RECOVERY_REVIEWED',
   jsonb_build_object('kind',p_kind,'operationId',p_operation,'reviewerDbLogin',session_user,
     'ticket',p_ticket),clock_timestamp(),clock_timestamp(),0,clock_timestamp());
 SELECT count(DISTINCT reviewer_db_login) INTO v_count
   FROM public.knowledge_retention_recovery_reviews_v89
   WHERE kind=p_kind AND operation_id=p_operation AND blocked_updated_at=p_blocked_at
     AND ticket_code=p_ticket AND evidence_sha256=p_evidence_sha256
     AND created_at>clock_timestamp()-interval '30 minutes';
 IF v_count<2 THEN RETURN 'REVIEW_RECORDED'; END IF;
 IF p_kind='DOCUMENT' THEN
   UPDATE public.knowledge_document_deletions_v78
     SET state='DELETING',next_attempt_at=clock_timestamp(),updated_at=clock_timestamp()
     WHERE id=p_operation AND state='BLOCKED_BY_RETENTION'
       AND updated_at=p_blocked_at AND lease_token IS NULL AND lease_until IS NULL;
 ELSE
   UPDATE public.knowledge_base_deletions_v82
     SET state='DELETING',next_attempt_at=clock_timestamp(),updated_at=clock_timestamp()
     WHERE id=p_operation AND state='BLOCKED_BY_RETENTION'
       AND updated_at=p_blocked_at AND lease_token IS NULL AND lease_until IS NULL;
 END IF;
 GET DIAGNOSTICS v_refcount = ROW_COUNT;
 IF v_refcount<>1 THEN RAISE EXCEPTION 'V89: stale blocked-state review'; END IF;
 INSERT INTO public.audit_outbox
 (id,actor_user_id,organization_id,event_type,details,occurred_at,created_at,attempt_count,next_attempt_at)
 VALUES(gen_random_uuid(),NULL,v_org,'KNOWLEDGE_RETENTION_RECOVERY_RESUMED',
   jsonb_build_object('kind',p_kind,'operationId',p_operation,'ticket',p_ticket,
     'reviewCount',v_count),clock_timestamp(),clock_timestamp(),0,clock_timestamp());
 -- Resumption is NOT an S3 bypass. The normal executor must verify all versions
 -- again. A still-locked version becomes BLOCKED_BY_RETENTION once more.
 RETURN 'RESUMED_FOR_REVERIFICATION';
END $fn$;
REVOKE ALL ON FUNCTION public.review_blocked_knowledge_erasure_v89(varchar,uuid,timestamptz,varchar,char(64)) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.review_blocked_knowledge_erasure_v89(varchar,uuid,timestamptz,varchar,char(64))
 TO safeai_knowledge_erasure_operator;
COMMENT ON FUNCTION public.review_blocked_knowledge_erasure_v89(varchar,uuid,timestamptz,varchar,char(64)) IS
 'Two distinct human DB logins, matching immutable evidence, and fresh blocked-state version. Worker re-verifies S3 Object Lock.';
