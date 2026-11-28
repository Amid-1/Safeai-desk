-- V108: least-privilege locking fix for V105 review and legal-hold guards.
-- V1..V107 are immutable. No executable permits and no new runtime grants.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = pg_catalog, pg_temp;
DO $v108_preflight$
DECLARE f record;
BEGIN
    IF current_user <> 'safeai_migrator' OR session_user <> current_user
       OR NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version='107' AND success) THEN
        RAISE EXCEPTION 'V108 requires isolated schema owner and successful V107';
    END IF;
    LOCK TABLE public.erasure_review_authorities_v105, public.erasure_reviews_v105,
        public.erasure_review_holds_v105 IN SHARE ROW EXCLUSIVE MODE NOWAIT;
    FOR f IN SELECT name,digest FROM (VALUES
        ('guard_erasure_review_v105','7f44138f0c8275cb3dcf80414f4bfcfd027a893360a8d485d83d5488ae972536'),
        ('guard_erasure_hold_v105','db7353bd45bf245f7ddb41fc932f4c4e9266695dcf5f9e8d41d9000a5239b192')
    ) v(name,digest) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid=format('public.%I()',f.name)::regprocedure
            AND p.proowner=current_user::regrole AND NOT p.prosecdef
            AND p.prokind='f' AND p.prorettype='pg_catalog.trigger'::regtype
            AND p.pronargs=0 AND p.provolatile='v'
            AND p.prolang=(SELECT oid FROM pg_language WHERE lanname='plpgsql')
            AND p.proconfig=ARRAY['search_path=pg_catalog, pg_temp']
            AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=f.digest) THEN
            RAISE EXCEPTION 'V108 unexpected historical guard: %',f.name;
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM public.erasure_gate_catalog_v107) OR EXISTS (
        WITH actual AS (SELECT 'FUNCTION'::text AS object_kind,p.oid::regprocedure::text AS object_name,
       encode(sha256(convert_to(pg_get_functiondef(p.oid),'UTF8')),'hex') AS digest
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='public' AND p.prokind='f' AND p.proname ~ '_v(44|67|86|103|104|105|106|107)$'
UNION ALL
SELECT 'TRIGGER',c.oid::regclass::text||':'||g.tgname,
       encode(sha256(convert_to(pg_get_triggerdef(g.oid,false)||'|'||g.tgenabled::text,'UTF8')),'hex')
FROM pg_trigger g JOIN pg_class c ON c.oid=g.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND NOT g.tgisinternal AND (g.tgname ~ '_v(44|67|86|103|104|105|106|107)(_row)?$' OR g.tgfoid IN
    (SELECT p.oid FROM pg_proc p JOIN pg_namespace pn ON pn.oid=p.pronamespace WHERE pn.nspname='public' AND p.proname ~ '_v(44|67|86|103|104|105|106|107)$'))), expected AS (SELECT object_kind,object_name,digest FROM public.erasure_gate_catalog_v107)
        (SELECT * FROM actual EXCEPT SELECT * FROM expected)
        UNION ALL (SELECT * FROM expected EXCEPT SELECT * FROM actual)
    ) THEN RAISE EXCEPTION 'V107 catalog definition drift'; END IF;
END;
$v108_preflight$;

-- The historical bodies are fully qualified, fixed-search-path, static SQL,
-- and authenticate the actor using the V105 transaction-bound HMAC.
-- Only the trigger execution context changes. Never grant UPDATE on the authority table.
ALTER FUNCTION public.guard_erasure_review_v105() SECURITY DEFINER;
ALTER FUNCTION public.guard_erasure_hold_v105() SECURITY DEFINER;
REVOKE ALL ON FUNCTION public.guard_erasure_review_v105(), public.guard_erasure_hold_v105()
    FROM PUBLIC, safeai_app, safeai_chat_erasure_worker, safeai_knowledge_erasure_worker,
         safeai_chat_erasure_operator, safeai_knowledge_erasure_operator;

-- V108: the two private V105 guards require row locks without granting UPDATE on authorities.
DO $v108_guard_acl$
DECLARE f record; r record;
BEGIN
    FOR f IN SELECT name,digest FROM (VALUES
        ('guard_erasure_review_v105','7f44138f0c8275cb3dcf80414f4bfcfd027a893360a8d485d83d5488ae972536'),
        ('guard_erasure_hold_v105','db7353bd45bf245f7ddb41fc932f4c4e9266695dcf5f9e8d41d9000a5239b192')
    ) v(name,digest) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid=format('public.%I()',f.name)::regprocedure
          AND p.proowner='safeai_migrator'::regrole AND p.prosecdef
          AND p.prokind='f' AND p.prorettype='pg_catalog.trigger'::regtype
          AND p.pronargs=0 AND p.provolatile='v'
          AND p.prolang=(SELECT oid FROM pg_language WHERE lanname='plpgsql')
          AND p.proconfig=ARRAY['search_path=pg_catalog, pg_temp']
          AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=f.digest)
          OR EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL
            aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
            WHERE p.oid=format('public.%I()',f.name)::regprocedure AND a.grantee<>p.proowner) THEN
            RAISE EXCEPTION 'V108 guard definition or direct ACL drift: %',f.name;
        END IF;
        FOR r IN SELECT name FROM (VALUES ('safeai_app'),('safeai_chat_erasure_worker'),
            ('safeai_knowledge_erasure_worker'),('safeai_chat_erasure_operator'),
            ('safeai_knowledge_erasure_operator')) v(name) LOOP
            IF has_function_privilege(r.name,format('public.%I()',f.name),'EXECUTE')
               OR has_table_privilege(r.name,'public.erasure_review_authorities_v105',
                    'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
               OR has_any_column_privilege(r.name,'public.erasure_review_authorities_v105',
                    'INSERT,UPDATE,REFERENCES') THEN
                RAISE EXCEPTION 'V108 authority mutation or guard privilege: % %',r.name,f.name;
            END IF;
        END LOOP;
    END LOOP;
END;
$v108_guard_acl$;

-- Compare against the original immutable V107 catalogue. Only the exact two
-- SECURITY DEFINER header lines are normalized; their new attributes, pinned
-- body digests, and private ACL are independently mandatory above.
DO $v108_catalog$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.erasure_gate_catalog_v107) OR EXISTS (
        WITH actual AS (SELECT 'FUNCTION'::text AS object_kind,p.oid::regprocedure::text AS object_name,
       encode(sha256(convert_to(CASE WHEN p.oid IN ('public.guard_erasure_review_v105()'::regprocedure, 'public.guard_erasure_hold_v105()'::regprocedure)
           THEN regexp_replace(pg_get_functiondef(p.oid), E'\n SECURITY DEFINER\n', E'\n')
           ELSE pg_get_functiondef(p.oid) END,'UTF8')),'hex') AS digest
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='public' AND p.prokind='f' AND p.proname ~ '_v(44|67|86|103|104|105|106|107)$'
UNION ALL
SELECT 'TRIGGER',c.oid::regclass::text||':'||g.tgname,
       encode(sha256(convert_to(pg_get_triggerdef(g.oid,false)||'|'||g.tgenabled::text,'UTF8')),'hex')
FROM pg_trigger g JOIN pg_class c ON c.oid=g.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND NOT g.tgisinternal AND (g.tgname ~ '_v(44|67|86|103|104|105|106|107)(_row)?$' OR g.tgfoid IN
    (SELECT p.oid FROM pg_proc p JOIN pg_namespace pn ON pn.oid=p.pronamespace WHERE pn.nspname='public' AND p.proname ~ '_v(44|67|86|103|104|105|106|107)$'))), expected AS (SELECT object_kind,object_name,digest FROM public.erasure_gate_catalog_v107)
        (SELECT * FROM actual EXCEPT SELECT * FROM expected)
        UNION ALL (SELECT * FROM expected EXCEPT SELECT * FROM actual)
    ) THEN RAISE EXCEPTION 'V107 catalog definition drift'; END IF;
END;
$v108_catalog$;

-- Additional pending-V108 hardening. Existing V103--V107 remain immutable.
-- Refuse inconsistent historical evidence; do not rewrite or silently release it.
LOCK TABLE public.erasure_previews_v103, public.erasure_requests_v103,
    public.erasure_reviews_v105, public.erasure_review_holds_v105,
    public.erasure_review_events_v105 IN SHARE ROW EXCLUSIVE MODE NOWAIT;
DO $v108_evidence_preflight$
BEGIN
    IF EXISTS (SELECT 1 FROM public.erasure_reviews_v105
        WHERE last_event_code IN ('APPROVED_REVIEW_ONLY','REJECTED','NEEDS_INFORMATION') AND reason_code IS NULL)
       OR EXISTS (SELECT 1 FROM public.erasure_review_events_v105
        WHERE event_code IN ('APPROVED_REVIEW_ONLY','REJECTED','NEEDS_INFORMATION') AND reason_code IS NULL) THEN
        RAISE EXCEPTION 'V108 historical review decision lacks reason; manual evidence review required';
    END IF;
    IF EXISTS (SELECT 1 FROM public.erasure_review_holds_v105 h
        JOIN public.erasure_requests_v103 b ON b.id=h.request_id AND b.organization_id=h.organization_id
        JOIN public.erasure_reviews_v105 r ON r.request_id=h.request_id AND r.organization_id=h.organization_id
        WHERE h.released_at IS NULL AND (b.state<>'SUBMITTED' OR r.review_state<>'PENDING')) THEN
        RAISE EXCEPTION 'V108 historical active hold has incompatible case state; legal review required';
    END IF;
END;
$v108_evidence_preflight$;

CREATE FUNCTION public.guard_erasure_request_fence_v108() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    -- The original V104 identity, transition and immutable-field checks still run.
    IF TG_OP='INSERT' OR NEW.state='SUBMITTED' THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.erasure_previews_v103 p
            WHERE p.id=NEW.preview_id AND p.organization_id=NEW.organization_id
              AND p.requester_user_id=NEW.requested_by_user_id
              AND p.resource_type=NEW.resource_type AND p.resource_id=NEW.resource_id
              AND p.operation=NEW.operation AND p.scope=NEW.scope
              AND p.resource_version=NEW.expected_resource_version
              AND p.preview_digest=NEW.preview_digest
              AND isfinite(p.created_at) AND isfinite(p.expires_at)
              AND p.created_at <= clock_timestamp()
              AND p.expires_at <= p.created_at + interval '15 minutes'
              AND p.expires_at > clock_timestamp()
        ) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V108_PREVIEW_NOT_LIVE';
        END IF;
    ELSIF OLD.state='SUBMITTED' AND NEW.state='WITHDRAWN' THEN
        -- Base row is already locked by UPDATE. Hold changes take the same
        -- base -> review order. Lock review to reject stale RR snapshots too.
        PERFORM 1 FROM public.erasure_reviews_v105 r
            WHERE r.request_id=OLD.id AND r.organization_id=OLD.organization_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V108_REVIEW_MISSING';
        END IF;
        IF EXISTS (SELECT 1 FROM public.erasure_review_holds_v105 h
            WHERE h.request_id=OLD.id AND h.organization_id=OLD.organization_id
              AND h.released_at IS NULL) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V108_ACTIVE_HOLD_BLOCKS_WITHDRAWAL';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_erasure_request_fence_v108() FROM PUBLIC,safeai_app,safeai_chat_erasure_worker,safeai_knowledge_erasure_worker,safeai_chat_erasure_operator,safeai_knowledge_erasure_operator;

CREATE FUNCTION public.lock_erasure_hold_case_v108() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    -- Align raw SQL with ErasureReviewService: base -> review -> V107 chat.
    -- BEFORE trigger executes before aa_v105_chat_hold_lock_v107.
    PERFORM 1 FROM public.erasure_requests_v103 r
        WHERE r.id=NEW.request_id AND r.organization_id=NEW.organization_id
          AND r.state='SUBMITTED' FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V108_HOLD_CASE_NOT_SUBMITTED';
    END IF;
    PERFORM 1 FROM public.erasure_reviews_v105 r
        WHERE r.request_id=NEW.request_id AND r.organization_id=NEW.organization_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V108_REVIEW_MISSING';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.lock_erasure_hold_case_v108() FROM PUBLIC,safeai_app,safeai_chat_erasure_worker,safeai_knowledge_erasure_worker,safeai_chat_erasure_operator,safeai_knowledge_erasure_operator;

CREATE FUNCTION public.guard_erasure_review_reason_v108() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    -- SQL three-valued logic in the V105 NOT (decision AND reason IN (...))
    -- must not turn a missing reason into an accepted review decision.
    IF NEW.last_event_code IN ('APPROVED_REVIEW_ONLY','REJECTED','NEEDS_INFORMATION')
       AND NEW.reason_code IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V108_REVIEW_REASON_REQUIRED';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_erasure_review_reason_v108() FROM PUBLIC,safeai_app,safeai_chat_erasure_worker,safeai_knowledge_erasure_worker,safeai_chat_erasure_operator,safeai_knowledge_erasure_operator;
CREATE TRIGGER zz_erasure_request_fence_v108 BEFORE INSERT OR UPDATE ON public.erasure_requests_v103
FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_request_fence_v108();
ALTER TABLE public.erasure_requests_v103 ENABLE ALWAYS TRIGGER zz_erasure_request_fence_v108;
CREATE TRIGGER a0_erasure_hold_case_lock_v108 BEFORE INSERT OR UPDATE ON public.erasure_review_holds_v105
FOR EACH ROW EXECUTE FUNCTION public.lock_erasure_hold_case_v108();
ALTER TABLE public.erasure_review_holds_v105 ENABLE ALWAYS TRIGGER a0_erasure_hold_case_lock_v108;
CREATE TRIGGER zz_erasure_review_reason_v108 BEFORE INSERT OR UPDATE ON public.erasure_reviews_v105
FOR EACH ROW EXECUTE FUNCTION public.guard_erasure_review_reason_v108();
ALTER TABLE public.erasure_reviews_v105 ENABLE ALWAYS TRIGGER zz_erasure_review_reason_v108;

-- Independent pinned inventory for the extra V108 guards (not present in V107 catalogue).
DO $v108_intake_guard_acl$
DECLARE f record; t record; r record;
BEGIN
    FOR f IN SELECT * FROM (VALUES
        ('guard_erasure_request_fence_v108','41cac1775095b9a6db35bbec563c51f3d91592000f417b6aa7e912cc7383b3ce'),
        ('lock_erasure_hold_case_v108','4e098dba66ad09c8ced3bf6ac92044fe747a9fabb829b43721531b552ea7ddda'),
        ('guard_erasure_review_reason_v108','32cef216be9d0e97590079dbb34ce99bf160e7401342b23c96fc7c659c38aff9')
    ) v(name,digest) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid=to_regprocedure(format('public.%I()',f.name))
            AND p.proowner='safeai_migrator'::regrole AND NOT p.prosecdef
            AND p.prokind='f' AND p.prorettype='pg_catalog.trigger'::regtype
            AND p.pronargs=0 AND p.provolatile='v' AND NOT p.proisstrict
            AND p.prolang=(SELECT oid FROM pg_language WHERE lanname='plpgsql')
            AND p.proconfig=ARRAY['search_path=pg_catalog, pg_temp']
            AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=f.digest)
          OR EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
            WHERE p.oid=to_regprocedure(format('public.%I()',f.name)) AND a.grantee<>p.proowner) THEN
            RAISE EXCEPTION 'V108 intake guard definition or ACL drift: %',f.name;
        END IF;
        FOR r IN SELECT name FROM (VALUES ('safeai_app'),('safeai_chat_erasure_worker'),
            ('safeai_knowledge_erasure_worker'),('safeai_chat_erasure_operator'),('safeai_knowledge_erasure_operator')) v(name) LOOP
            IF has_function_privilege(r.name,format('public.%I()',f.name),'EXECUTE') THEN
                RAISE EXCEPTION 'V108 intake guard EXECUTE drift: % %',r.name,f.name;
            END IF;
        END LOOP;
    END LOOP;
    IF (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='public' AND p.proname ~ '_v108$') <> 3 THEN
        RAISE EXCEPTION 'V108 intake guard routine inventory drift';
    END IF;
    FOR t IN SELECT * FROM (VALUES
        ('zz_erasure_request_fence_v108','erasure_requests_v103','guard_erasure_request_fence_v108'),
        ('a0_erasure_hold_case_lock_v108','erasure_review_holds_v105','lock_erasure_hold_case_v108'),
        ('zz_erasure_review_reason_v108','erasure_reviews_v105','guard_erasure_review_reason_v108')
    ) v(name,rel,fn) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger g
            WHERE g.tgrelid=format('public.%I',t.rel)::regclass AND g.tgname=t.name
            AND g.tgfoid=to_regprocedure(format('public.%I()',t.fn))
            AND g.tgtype=23 AND g.tgenabled='A' AND NOT g.tgisinternal
            AND g.tgnargs=0 AND g.tgqual IS NULL AND cardinality(g.tgattr::smallint[])=0
            AND g.tgconstraint=0 AND g.tgoldtable IS NULL AND g.tgnewtable IS NULL) THEN
            RAISE EXCEPTION 'V108 intake guard trigger drift: %',t.name;
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM pg_trigger g JOIN pg_class c ON c.oid=g.tgrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND NOT g.tgisinternal AND g.tgname ~ '_v108$') <> 3 THEN
        RAISE EXCEPTION 'V108 intake guard trigger inventory drift';
    END IF;
END;
$v108_intake_guard_acl$;

-- V108 forward-only repair: V107 is already recorded in persistent history.
-- Never rewrite V107 or its immutable catalogue. The exact original body and
-- the complete V107 catalogue have been verified above before changing anything.
SET LOCAL search_path=pg_catalog,pg_temp;
LOCK TABLE public.chat_execution_cutover_v106, public.erasure_execution_permits_v106,
    public.erasure_gate_audit_v107 IN SHARE ROW EXCLUSIVE MODE NOWAIT;
DO $v108_audit_original$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_proc p
        WHERE p.oid=to_regprocedure('public.audit_erasure_gate_v107()')
          AND p.proowner='safeai_migrator'::regrole AND NOT p.prosecdef
          AND p.prokind='f' AND p.prorettype='pg_catalog.trigger'::regtype
          AND p.pronargs=0 AND p.provolatile='v' AND NOT p.proisstrict
          AND p.prolang=(SELECT oid FROM pg_language WHERE lanname='plpgsql')
          AND p.proconfig=ARRAY['search_path=pg_catalog, pg_temp']
          AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')='a677a760c62ed6d1c78caf29226eab189e33b2b4773c39d0acbcdf5292b94f20')
       OR EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL
          aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
          WHERE p.oid=to_regprocedure('public.audit_erasure_gate_v107()') AND a.grantee<>p.proowner) THEN
        RAISE EXCEPTION 'V108 audit function definition or ACL drift';
    END IF;
    IF EXISTS (SELECT 1 FROM (VALUES ('safeai_app'),('safeai_chat_erasure_worker'),
         ('safeai_knowledge_erasure_worker'),('safeai_chat_erasure_operator'),('safeai_knowledge_erasure_operator')) r(name)
        WHERE has_function_privilege(r.name,'public.audit_erasure_gate_v107()','EXECUTE')) THEN
        RAISE EXCEPTION 'V108 audit function runtime EXECUTE drift';
    END IF;
END;
$v108_audit_original$;

CREATE OR REPLACE FUNCTION public.audit_erasure_gate_v107() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    -- RECORD fields are table-specific. Resolve the relation in a separate
    -- procedural branch before preparing any expression using OLD/NEW fields.
    IF TG_TABLE_SCHEMA IS DISTINCT FROM 'public' OR TG_OP IS DISTINCT FROM 'UPDATE'
       OR TG_LEVEL IS DISTINCT FROM 'ROW' OR TG_WHEN IS DISTINCT FROM 'AFTER'
       OR TG_NARGS IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_AUDIT_TRIGGER_CONTEXT_INVALID';
    END IF;
    IF TG_RELID = 'public.chat_execution_cutover_v106'::regclass THEN
        IF OLD.mode IS DISTINCT FROM 'LEGACY_COMPATIBILITY' OR NEW.mode IS DISTINCT FROM 'SEALED' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_AUDIT_CUTOVER_TRANSITION_INVALID';
        END IF;
        INSERT INTO public.erasure_gate_audit_v107(event_type,database_login,transaction_id)
        VALUES('CUTOVER_SEALED',session_user,txid_current());
    ELSIF TG_RELID = 'public.erasure_execution_permits_v106'::regclass THEN
        IF OLD.state IS DISTINCT FROM 'RECORDED_ONLY' OR NEW.state IS DISTINCT FROM 'REVOKED'
           OR NEW.permit_id IS DISTINCT FROM OLD.permit_id THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_AUDIT_PERMIT_TRANSITION_INVALID';
        END IF;
        INSERT INTO public.erasure_gate_audit_v107(event_type,subject_id,database_login,transaction_id)
        VALUES('CANDIDATE_REVOKED',NEW.permit_id,session_user,txid_current());
    ELSE
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_AUDIT_TRIGGER_CONTEXT_INVALID';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.audit_erasure_gate_v107()
    FROM PUBLIC,safeai_app,safeai_chat_erasure_worker,safeai_knowledge_erasure_worker,
         safeai_chat_erasure_operator,safeai_knowledge_erasure_operator;

-- The historical catalogue stays byte-for-byte unchanged. Verify new body,
-- ownership and ACL, then reconstruct only the reviewed historical body for
-- full bidirectional catalogue comparison (all other metadata still checked).
DO $v108_audit_guard_acl$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_proc p
        WHERE p.oid=to_regprocedure('public.audit_erasure_gate_v107()')
          AND p.proowner='safeai_migrator'::regrole AND NOT p.prosecdef
          AND p.prokind='f' AND p.prorettype='pg_catalog.trigger'::regtype
          AND p.pronargs=0 AND p.provolatile='v' AND NOT p.proisstrict
          AND p.prolang=(SELECT oid FROM pg_language WHERE lanname='plpgsql')
          AND p.proconfig=ARRAY['search_path=pg_catalog, pg_temp']
          AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')='e81671ad36d3d15fe239a0af81f801cdb14abfa9745718385ecf650ab7ee6d77')
       OR EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL
          aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
          WHERE p.oid=to_regprocedure('public.audit_erasure_gate_v107()') AND a.grantee<>p.proowner) THEN
        RAISE EXCEPTION 'V108 audit function definition or ACL drift';
    END IF;
    IF EXISTS (SELECT 1 FROM (VALUES ('safeai_app'),('safeai_chat_erasure_worker'),
         ('safeai_knowledge_erasure_worker'),('safeai_chat_erasure_operator'),('safeai_knowledge_erasure_operator')) r(name)
        WHERE has_function_privilege(r.name,'public.audit_erasure_gate_v107()','EXECUTE')) THEN
        RAISE EXCEPTION 'V108 audit function runtime EXECUTE drift';
    END IF;
END;
$v108_audit_guard_acl$;

DO $v108_audit_catalog$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.erasure_gate_catalog_v107) OR EXISTS (
        WITH actual AS (SELECT 'FUNCTION'::text AS object_kind,p.oid::regprocedure::text AS object_name,
       encode(sha256(convert_to(CASE WHEN p.oid='public.audit_erasure_gate_v107()'::regprocedure
           THEN replace(pg_get_functiondef(p.oid),p.prosrc,$v107_audit_original$
BEGIN
    IF TG_TABLE_NAME='chat_execution_cutover_v106' AND NEW.mode='SEALED' THEN
        INSERT INTO public.erasure_gate_audit_v107(event_type,database_login,transaction_id)
        VALUES('CUTOVER_SEALED',session_user,txid_current());
    ELSIF TG_TABLE_NAME='erasure_execution_permits_v106' THEN
        IF NEW.state='REVOKED' THEN
            INSERT INTO public.erasure_gate_audit_v107(event_type,subject_id,database_login,transaction_id)
            VALUES('CANDIDATE_REVOKED',NEW.permit_id,session_user,txid_current());
        END IF;
    END IF;
    RETURN NULL;
END;
$v107_audit_original$)
           WHEN p.oid IN ('public.guard_erasure_review_v105()'::regprocedure, 'public.guard_erasure_hold_v105()'::regprocedure)
           THEN regexp_replace(pg_get_functiondef(p.oid), E'\n SECURITY DEFINER\n', E'\n')
           ELSE pg_get_functiondef(p.oid) END,'UTF8')),'hex') AS digest
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='public' AND p.prokind='f' AND p.proname ~ '_v(44|67|86|103|104|105|106|107)$'
UNION ALL
SELECT 'TRIGGER',c.oid::regclass::text||':'||g.tgname,
       encode(sha256(convert_to(pg_get_triggerdef(g.oid,false)||'|'||g.tgenabled::text,'UTF8')),'hex')
FROM pg_trigger g JOIN pg_class c ON c.oid=g.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND NOT g.tgisinternal AND (g.tgname ~ '_v(44|67|86|103|104|105|106|107)(_row)?$' OR g.tgfoid IN
    (SELECT p.oid FROM pg_proc p JOIN pg_namespace pn ON pn.oid=p.pronamespace WHERE pn.nspname='public' AND p.proname ~ '_v(44|67|86|103|104|105|106|107)$'))), expected AS (SELECT object_kind,object_name,digest FROM public.erasure_gate_catalog_v107)
        (SELECT * FROM actual EXCEPT SELECT * FROM expected)
        UNION ALL (SELECT * FROM expected EXCEPT SELECT * FROM actual)
    ) THEN RAISE EXCEPTION 'V107 catalog definition drift'; END IF;
END;
$v108_audit_catalog$;
