-- SafeAI-Desk/backend/src/main/resources/db/migration/V107__chat_execution_hardening.sql
-- Forward-only hardening. V106 remains immutable. No executable permit, no new runtime grant.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = pg_catalog, pg_temp;
DO $preflight$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version='106' AND success) THEN
        RAISE EXCEPTION 'V107 requires successful V106';
    END IF;
    IF session_user IN ('safeai_app','safeai_chat_erasure_worker','safeai_knowledge_erasure_worker') THEN
        RAISE EXCEPTION 'V107 requires isolated schema owner';
    END IF;
    LOCK TABLE public.chat_sessions, public.chat_erasure_operations_v86, public.chat_messages,
        public.knowledge_retrieval_runs, public.chat_retention_holds_v86, public.erasure_review_holds_v105
        IN SHARE ROW EXCLUSIVE MODE NOWAIT;
    IF EXISTS (SELECT 1 FROM public.chat_erasure_operations_v86
        WHERE state NOT IN ('ERASED','CANCELLED') OR lease_token IS NOT NULL OR lease_until IS NOT NULL)
       OR EXISTS (SELECT 1 FROM public.chat_sessions WHERE erasure_state='ERASING') THEN
        RAISE EXCEPTION 'V107 requires drained legacy erasure and maintenance window';
    END IF;
END;
$preflight$;
DO $original$
DECLARE f record;
BEGIN
    FOR f IN SELECT * FROM (VALUES
        ('guard_chat_execution_cutover_v106','30a312917f2552a1f27ca173346a2b4ca4c67adac4e559a90f8b3413e9888f8d'),
        ('guard_chat_execution_intent_v106','089b0ed6e43347bda08473ab227796a9ea7b0efdb74eb08198af9711b3fc13a4'),
        ('guard_erasure_epochs_v106','610cf8395bd62e8cbde3930ff2a2ac55c1d54e5446468313e2430b7be38d168d'),
        ('bump_erasure_epochs_v106','1d076279ef167786e96f8e9a9854ab1e3bed765767b065bbffec98e6ecf5c827'),
        ('guard_erasure_permit_v106','c22ab592e919cc97342efd4bea29f775c81a127ccf49e24c84dbce3a70008041'),
        ('reserve_erasure_identifiers_v106','7cc1563e65873dcf4ecafda10e909cd3c77aa7a572fc8f631f9277519ab46201'),
        ('guard_erasure_identifiers_v106','2d3dedb5df3beb2a61f15cf9cd96913620019b6b3a650a916fd7a785d044d251')
    ) v(name,digest) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid=format('public.%I()',f.name)::regprocedure
          AND p.proowner=current_user::regrole
          AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=f.digest) THEN
            RAISE EXCEPTION 'Unexpected V106 function body: %',f.name;
        END IF;
    END LOOP;
END;
$original$;
DO $legacy_bodies$
DECLARE f record;
BEGIN
    FOR f IN SELECT * FROM (VALUES
        ('public.guard_chat_erasure_intent_insert_v86()','32b8fac1ed400a241220a8adc51ca514faa61e0c619ad16e8453084b667b492a'),
        ('public.guard_chat_erasure_operation_v86()','a1df07d7b4b73fa881ae40592f9566541d20385494fb481972ba010143a2419a'),
        ('public.guard_chat_session_erasure_v86()','f146147c1d25474f5bbf8ae0b8063af18c176fd680eca9c8b7486332baaf6d7c'),
        ('public.chat_messages_erasure_guard_v86()','1b37ccb2e4a79dfdec87b3defd12734aae103825b3b4cb2f14112304367ea6a1'),
        ('public.chat_hold_insert_guard_v86()','aa3f81113f2eba409f8777c6bbd8a3decdff822c7eef2ed520a06d57f6e3545b'),
        ('public.chat_erasure_authorized_v86(uuid)','1ec62e31f0dc4608507f37ca6c08c500ee6665c5dfd09e9bc2a37fb563b524c3'),
        ('public.guard_erasure_hold_v105()','db7353bd45bf245f7ddb41fc932f4c4e9266695dcf5f9e8d41d9000a5239b192'),
        ('public.sync_erasure_review_hold_v105()','efbe3540e77184f79b58baa1ff8a923c1e39a3ee93f80b0cf603e11b8d15dd27')
    ) v(signature,digest) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid=f.signature::regprocedure
            AND p.proowner=current_user::regrole
            AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=f.digest) THEN
            RAISE EXCEPTION 'V107 historical guard definition drift: %',f.signature;
        END IF;
    END LOOP;
END;
$legacy_bodies$;

DO $v106_triggers$
DECLARE t record; columns_expected text;
BEGIN
    FOR t IN SELECT * FROM (VALUES
        ('trg_chat_execution_cutover_guard_v106','chat_execution_cutover_v106','guard_chat_execution_cutover_v106',31,'',false),
        ('trg_chat_execution_cutover_no_truncate_v106','chat_execution_cutover_v106','guard_chat_execution_cutover_v106',34,'',false),
        ('trg_chat_admission_gate_v106','chat_erasure_operations_v86','guard_chat_execution_intent_v106',23,'',false),
        ('trg_chat_execution_session_gate_v106','chat_sessions','guard_chat_execution_intent_v106',19,'erasure_state',true),
        ('trg_chat_execution_worker_session_v106','chat_sessions','guard_chat_execution_intent_v106',19,'',true),
        ('trg_chat_execution_worker_message_v106','chat_messages','guard_chat_execution_intent_v106',19,'content',true),
        ('trg_chat_execution_worker_retrieval_v106','knowledge_retrieval_runs','guard_chat_execution_intent_v106',19,'query_text',true),
        ('trg_erasure_epochs_guard_v106','erasure_execution_epochs_v106','guard_erasure_epochs_v106',31,'',false),
        ('trg_erasure_epochs_truncate_v106','erasure_execution_epochs_v106','guard_erasure_epochs_v106',34,'',false),
        ('trg_erasure_authority_epoch_v106','erasure_review_authorities_v105','bump_erasure_epochs_v106',60,'',false),
        ('trg_erasure_review_key_epoch_v106','erasure_review_actor_keys_v105','bump_erasure_epochs_v106',60,'',false),
        ('trg_erasure_role_epoch_v106','user_roles','bump_erasure_epochs_v106',60,'',false),
        ('trg_erasure_role_catalog_epoch_v106','roles','bump_erasure_epochs_v106',60,'',false),
        ('trg_erasure_user_epoch_v106','users','bump_erasure_epochs_v106',44,'',false),
        ('trg_erasure_user_auth_epoch_v106','users','bump_erasure_epochs_v106',16,'enabled,token_version,organization_id,password_hash',false),
        ('trg_erasure_org_epoch_v106','organizations','bump_erasure_epochs_v106',44,'',false),
        ('trg_erasure_org_auth_epoch_v106','organizations','bump_erasure_epochs_v106',16,'enabled,auth_version',false),
        ('trg_erasure_policy_epoch_v106','platform_erasure_policy_v102','bump_erasure_epochs_v106',60,'',false),
        ('trg_erasure_v86_hold_epoch_v106','chat_retention_holds_v86','bump_erasure_epochs_v106',60,'',false),
        ('trg_erasure_v105_hold_epoch_v106','erasure_review_holds_v105','bump_erasure_epochs_v106',60,'',false),
        ('trg_erasure_permit_guard_v106','erasure_execution_permits_v106','guard_erasure_permit_v106',31,'',false),
        ('trg_erasure_permit_truncate_v106','erasure_execution_permits_v106','guard_erasure_permit_v106',34,'',false),
        ('trg_erasure_permit_reserve_v106','erasure_execution_permits_v106','reserve_erasure_identifiers_v106',5,'',false),
        ('trg_erasure_identifier_guard_v106','erasure_execution_identifiers_v106','guard_erasure_identifiers_v106',31,'',false),
        ('trg_erasure_identifier_truncate_v106','erasure_execution_identifiers_v106','guard_erasure_identifiers_v106',34,'',false)
    ) v(name,relation_name,function_name,event_mask,update_columns,has_condition) LOOP
        SELECT coalesce(string_agg(a.attnum::text,' ' ORDER BY a.attnum),'') INTO columns_expected
        FROM unnest(string_to_array(t.update_columns,',')) WITH ORDINALITY col(name,position)
        JOIN pg_attribute a ON a.attrelid=format('public.%I',t.relation_name)::regclass
          AND a.attname=col.name AND a.attnum>0 AND NOT a.attisdropped;
        IF NOT EXISTS (SELECT 1 FROM pg_trigger g WHERE g.tgrelid=format('public.%I',t.relation_name)::regclass
            AND g.tgname=t.name AND g.tgfoid=format('public.%I()',t.function_name)::regprocedure
            AND g.tgtype=t.event_mask AND (SELECT coalesce(string_agg(n::text,' ' ORDER BY n),'') FROM unnest(g.tgattr::smallint[]) AS n)=columns_expected
            AND (g.tgqual IS NOT NULL)=t.has_condition AND g.tgenabled='A' AND NOT g.tgisinternal) THEN
            RAISE EXCEPTION 'V107 unexpected V106 trigger structure: %',t.name;
        END IF;
    END LOOP;
END;
$v106_triggers$;

CREATE OR REPLACE FUNCTION public.guard_erasure_permit_v106() RETURNS trigger
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
        observed_at := clock_timestamp();
        IF NEW.issued_at > observed_at OR NEW.expires_at <= observed_at THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_PERMIT_EXPIRED_AFTER_LOCK';
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

-- Check again after the UNIQUE reservation, which can also block.
CREATE FUNCTION public.check_permit_after_reservation_v107() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF NEW.issued_at > clock_timestamp() OR NEW.expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='ERASURE_PERMIT_EXPIRED_AFTER_RESERVATION';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER zz_permit_after_reservation_v107 AFTER INSERT ON public.erasure_execution_permits_v106
FOR EACH ROW EXECUTE FUNCTION public.check_permit_after_reservation_v107();
ALTER TABLE public.erasure_execution_permits_v106 ENABLE ALWAYS TRIGGER zz_permit_after_reservation_v107;

CREATE INDEX ix_erasure_requests_chat_resource_v107 ON public.erasure_requests_v103(organization_id,resource_id) WHERE resource_type='CHAT';

-- V105 hold insertion takes the same chat-row fence as V86 finalization.
-- A hold cannot be reported active on an already erased chat. Release keeps
-- historical V105 semantics. V86 cancellation and NEEDS_REVIEW stay possible.
CREATE FUNCTION public.lock_v105_chat_hold_v107() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE r record; chat_state text;
BEGIN
    SELECT resource_type,resource_id INTO r FROM public.erasure_requests_v103
        WHERE id=NEW.request_id AND organization_id=NEW.organization_id;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_HOLD_REQUEST_MISSING'; END IF;
    IF r.resource_type='CHAT' THEN
        SELECT erasure_state INTO chat_state FROM public.chat_sessions
            WHERE id=r.resource_id AND organization_id=NEW.organization_id FOR UPDATE;
        IF NOT FOUND OR chat_state='ERASED' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_HOLD_CHAT_MISSING_OR_ERASED';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER aa_v105_chat_hold_lock_v107 BEFORE INSERT ON public.erasure_review_holds_v105
FOR EACH ROW EXECUTE FUNCTION public.lock_v105_chat_hold_v107();
ALTER TABLE public.erasure_review_holds_v105 ENABLE ALWAYS TRIGGER aa_v105_chat_hold_lock_v107;

-- At each irreversible V86 path, serialize on chat, then read the committed
-- hold epoch with a row lock. At RR/SERIALIZABLE a changed epoch aborts a stale
-- snapshot (40001); at RC the subsequent VOLATILE SQL read sees committed holds.
-- Existing V86 guards, dedicated login, lease and operator checks remain intact.
CREATE FUNCTION public.guard_combined_chat_holds_v107() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE target_chat uuid; tenant uuid; state_now text; hold_epoch bigint;
BEGIN
    IF TG_TABLE_NAME='chat_erasure_operations_v86' THEN
        IF TG_OP='UPDATE' THEN
            -- Allow lease acquisition/renewal so worker can safely mark review.
            -- Do not allow operator resume or terminal erasure with a hold.
            IF NEW.state <> 'ERASED' AND NOT (NEW.state='ERASING' AND OLD.state<>'ERASING') THEN
                RETURN NEW;
            END IF;
        END IF;
        target_chat:=NEW.chat_id; tenant:=NEW.organization_id;
    ELSIF TG_TABLE_NAME='chat_sessions' THEN
        IF NEW.erasure_state NOT IN ('ERASING','ERASED') THEN RETURN NEW; END IF;
        target_chat:=OLD.id; tenant:=OLD.organization_id;
    ELSIF TG_TABLE_NAME='chat_messages' THEN
        IF session_user<>'safeai_chat_erasure_worker' THEN RETURN NEW; END IF;
        target_chat:=OLD.session_id; tenant:=OLD.organization_id;
    ELSIF TG_TABLE_NAME='knowledge_retrieval_runs' THEN
        IF session_user<>'safeai_chat_erasure_worker' THEN RETURN NEW; END IF;
        SELECT t.session_id,t.organization_id INTO target_chat,tenant FROM public.chat_turns t
            WHERE t.id=OLD.chat_turn_id;
        IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_CHAT_TURN_MISSING'; END IF;
    ELSE RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_UNEXPECTED_HOLD_GUARD';
    END IF;
    SELECT erasure_state INTO state_now FROM public.chat_sessions
        WHERE id=target_chat AND organization_id=tenant FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_CHAT_MISSING'; END IF;
    SELECT all_holds_epoch INTO hold_epoch FROM public.erasure_execution_epochs_v106 WHERE singleton FOR SHARE;
    IF NOT FOUND OR hold_epoch IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_HOLD_EPOCH_MISSING'; END IF;
    IF EXISTS (SELECT 1 FROM public.chat_retention_holds_v86 h
        WHERE h.chat_id=target_chat AND h.organization_id=tenant AND h.released_at IS NULL)
       OR EXISTS (SELECT 1 FROM public.erasure_review_holds_v105 h
          JOIN public.erasure_requests_v103 r ON r.id=h.request_id AND r.organization_id=h.organization_id
          WHERE r.organization_id=tenant AND r.resource_type='CHAT' AND r.resource_id=target_chat
            AND h.released_at IS NULL) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_COMBINED_LEGAL_HOLD';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER aa_combined_operation_hold_v107 BEFORE INSERT OR UPDATE ON public.chat_erasure_operations_v86
FOR EACH ROW EXECUTE FUNCTION public.guard_combined_chat_holds_v107();
CREATE TRIGGER aa_combined_session_hold_v107 BEFORE UPDATE ON public.chat_sessions
FOR EACH ROW WHEN (OLD.erasure_state IS DISTINCT FROM NEW.erasure_state)
EXECUTE FUNCTION public.guard_combined_chat_holds_v107();
CREATE TRIGGER aa_combined_message_hold_v107 BEFORE UPDATE ON public.chat_messages
FOR EACH ROW WHEN (session_user='safeai_chat_erasure_worker') EXECUTE FUNCTION public.guard_combined_chat_holds_v107();
CREATE TRIGGER aa_combined_retrieval_hold_v107 BEFORE UPDATE ON public.knowledge_retrieval_runs
FOR EACH ROW WHEN (session_user='safeai_chat_erasure_worker') EXECUTE FUNCTION public.guard_combined_chat_holds_v107();
ALTER TABLE public.chat_erasure_operations_v86 ENABLE ALWAYS TRIGGER aa_combined_operation_hold_v107;
ALTER TABLE public.chat_sessions ENABLE ALWAYS TRIGGER aa_combined_session_hold_v107;
ALTER TABLE public.chat_messages ENABLE ALWAYS TRIGGER aa_combined_message_hold_v107;
ALTER TABLE public.knowledge_retrieval_runs ENABLE ALWAYS TRIGGER aa_combined_retrieval_hold_v107;

-- Preserve the original statement-level UPDATE OF triggers (including no-op
-- invalidation). Additional actual OLD/NEW guards also catch changes made by
-- BEFORE triggers or updates to actor identity. Explicit auth changes may
-- bump twice: monotonic, conservative, and no cost for unrelated user edits.
CREATE TRIGGER trg_v107_user_actual_auth_epoch_v107 AFTER UPDATE ON public.users
FOR EACH ROW WHEN (OLD.id IS DISTINCT FROM NEW.id OR OLD.organization_id IS DISTINCT FROM NEW.organization_id OR OLD.enabled IS DISTINCT FROM NEW.enabled OR OLD.token_version IS DISTINCT FROM NEW.token_version OR OLD.password_hash IS DISTINCT FROM NEW.password_hash) EXECUTE FUNCTION public.bump_erasure_epochs_v106();
ALTER TABLE public.users ENABLE ALWAYS TRIGGER trg_v107_user_actual_auth_epoch_v107;
CREATE TRIGGER trg_v107_org_actual_auth_epoch_v107 AFTER UPDATE ON public.organizations
FOR EACH ROW WHEN (OLD.id IS DISTINCT FROM NEW.id OR OLD.enabled IS DISTINCT FROM NEW.enabled OR OLD.auth_version IS DISTINCT FROM NEW.auth_version) EXECUTE FUNCTION public.bump_erasure_epochs_v106();
ALTER TABLE public.organizations ENABLE ALWAYS TRIGGER trg_v107_org_actual_auth_epoch_v107;

-- Scoped epochs are additional observations only; the global V106 counters
-- remain mandatory until all callers use a formally reviewed scoped contract.
-- No FK to actors/resources: deletion must not reset the counter.
CREATE TABLE public.erasure_scoped_epochs_v107 (
    scope_kind text NOT NULL CHECK (scope_kind IN ('PLATFORM','ORGANIZATION','CHAT','KNOWLEDGE_DOCUMENT','KNOWLEDGE_BASE','USER_ACCOUNT')),
    organization_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    epoch bigint NOT NULL CHECK (epoch>=0),
    PRIMARY KEY(scope_kind,organization_id,resource_id),
    CHECK ((scope_kind='PLATFORM' AND organization_id='00000000-0000-0000-0000-000000000000'::uuid AND resource_id=organization_id)
      OR (scope_kind='ORGANIZATION' AND organization_id<>'00000000-0000-0000-0000-000000000000'::uuid AND resource_id=organization_id)
      OR (scope_kind NOT IN ('PLATFORM','ORGANIZATION') AND organization_id<>'00000000-0000-0000-0000-000000000000'::uuid AND resource_id<>'00000000-0000-0000-0000-000000000000'::uuid))
);
INSERT INTO public.erasure_scoped_epochs_v107 VALUES ('PLATFORM','00000000-0000-0000-0000-000000000000','00000000-0000-0000-0000-000000000000',0);
CREATE FUNCTION public.guard_scoped_epochs_v107() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF TG_OP='INSERT' AND NEW.scope_kind<>'PLATFORM' AND NEW.epoch=1 THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND ROW(NEW.scope_kind,NEW.organization_id,NEW.resource_id) IS NOT DISTINCT FROM
       ROW(OLD.scope_kind,OLD.organization_id,OLD.resource_id) AND NEW.epoch=OLD.epoch+1 THEN RETURN NEW; END IF;
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_SCOPED_EPOCH_IMMUTABLE';
END;
$$;
CREATE TRIGGER trg_scoped_epoch_guard_v107 BEFORE INSERT OR UPDATE OR DELETE ON public.erasure_scoped_epochs_v107
FOR EACH ROW EXECUTE FUNCTION public.guard_scoped_epochs_v107();
CREATE TRIGGER trg_scoped_epoch_no_truncate_v107 BEFORE TRUNCATE ON public.erasure_scoped_epochs_v107
FOR EACH STATEMENT EXECUTE FUNCTION public.guard_scoped_epochs_v107();
ALTER TABLE public.erasure_scoped_epochs_v107 ENABLE ALWAYS TRIGGER trg_scoped_epoch_guard_v107;
ALTER TABLE public.erasure_scoped_epochs_v107 ENABLE ALWAYS TRIGGER trg_scoped_epoch_no_truncate_v107;

-- Single shared trigger uses OLD/NEW and increments both affected tenant scopes
-- on a move. Global scope is deliberately conservative for role catalog/key
-- changes, truncate and missing user-role parent (e.g. cascading deletion).
CREATE FUNCTION public.bump_scoped_epochs_v107() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE old_org uuid; new_org uuid; old_resource uuid; new_resource uuid;
        old_kind text; new_kind text; s record;
        nil constant uuid := '00000000-0000-0000-0000-000000000000';
BEGIN
    IF TG_TABLE_SCHEMA <> 'public' OR TG_NARGS <> 0 THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_SCOPE_TRIGGER_INVALID';
    END IF;
    IF TG_OP='TRUNCATE' OR TG_TABLE_NAME IN ('roles','platform_erasure_policy_v102','erasure_review_actor_keys_v105') THEN
        UPDATE public.erasure_scoped_epochs_v107 SET epoch=epoch+1 WHERE scope_kind='PLATFORM' AND organization_id=nil AND resource_id=nil;
        IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_PLATFORM_EPOCH_MISSING'; END IF;
        RETURN NULL;
    END IF;
    IF TG_TABLE_NAME IN ('users','organizations','user_roles','erasure_review_authorities_v105') THEN
        old_kind:='ORGANIZATION'; new_kind:='ORGANIZATION';
        IF TG_TABLE_NAME='organizations' THEN
            IF TG_OP<>'INSERT' THEN old_org:=OLD.id; END IF;
            IF TG_OP<>'DELETE' THEN new_org:=NEW.id; END IF;
        ELSIF TG_TABLE_NAME='user_roles' THEN
            IF TG_OP<>'INSERT' THEN SELECT organization_id INTO old_org FROM public.users WHERE id=OLD.user_id; END IF;
            IF TG_OP<>'DELETE' THEN SELECT organization_id INTO new_org FROM public.users WHERE id=NEW.user_id; END IF;
            IF (TG_OP<>'INSERT' AND old_org IS NULL) OR (TG_OP<>'DELETE' AND new_org IS NULL) THEN
                UPDATE public.erasure_scoped_epochs_v107 SET epoch=epoch+1 WHERE scope_kind='PLATFORM' AND organization_id=nil AND resource_id=nil;
                IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_PLATFORM_EPOCH_MISSING'; END IF;
            END IF;
        ELSE
            IF TG_OP<>'INSERT' THEN old_org:=OLD.organization_id; END IF;
            IF TG_OP<>'DELETE' THEN new_org:=NEW.organization_id; END IF;
        END IF;
        old_resource:=old_org; new_resource:=new_org;
    ELSIF TG_TABLE_NAME='chat_retention_holds_v86' THEN
        old_kind:='CHAT'; new_kind:='CHAT';
        IF TG_OP<>'INSERT' THEN old_org:=OLD.organization_id; old_resource:=OLD.chat_id; END IF;
        IF TG_OP<>'DELETE' THEN new_org:=NEW.organization_id; new_resource:=NEW.chat_id; END IF;
    ELSIF TG_TABLE_NAME='erasure_review_holds_v105' THEN
        IF TG_OP<>'INSERT' THEN
            SELECT organization_id,resource_id,resource_type INTO old_org,old_resource,old_kind
                FROM public.erasure_requests_v103 WHERE id=OLD.request_id AND organization_id=OLD.organization_id;
            IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_SCOPE_REQUEST_MISSING'; END IF;
        END IF;
        IF TG_OP<>'DELETE' THEN
            SELECT organization_id,resource_id,resource_type INTO new_org,new_resource,new_kind
                FROM public.erasure_requests_v103 WHERE id=NEW.request_id AND organization_id=NEW.organization_id;
            IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_SCOPE_REQUEST_MISSING'; END IF;
        END IF;
    ELSE RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_SCOPE_TRIGGER_INVALID';
    END IF;
    FOR s IN SELECT DISTINCT kind,org,res FROM (VALUES(old_kind,old_org,old_resource),(new_kind,new_org,new_resource)) v(kind,org,res)
        WHERE org IS NOT NULL AND res IS NOT NULL ORDER BY kind,org,res LOOP
        INSERT INTO public.erasure_scoped_epochs_v107(scope_kind,organization_id,resource_id,epoch)
        VALUES(s.kind,s.org,s.res,1) ON CONFLICT(scope_kind,organization_id,resource_id)
        DO UPDATE SET epoch=erasure_scoped_epochs_v107.epoch+1;
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_scope_users_v107_row AFTER INSERT OR DELETE ON public.users
FOR EACH ROW EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.users ENABLE ALWAYS TRIGGER trg_scope_users_v107_row;
CREATE TRIGGER trg_scope_users_actual_v107 AFTER UPDATE ON public.users
FOR EACH ROW WHEN (OLD.id IS DISTINCT FROM NEW.id OR OLD.organization_id IS DISTINCT FROM NEW.organization_id OR OLD.enabled IS DISTINCT FROM NEW.enabled OR OLD.token_version IS DISTINCT FROM NEW.token_version OR OLD.password_hash IS DISTINCT FROM NEW.password_hash) EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.users ENABLE ALWAYS TRIGGER trg_scope_users_actual_v107;
CREATE TRIGGER trg_scope_users_v107 AFTER TRUNCATE ON public.users FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.users ENABLE ALWAYS TRIGGER trg_scope_users_v107;
CREATE TRIGGER trg_scope_organizations_v107_row AFTER INSERT OR DELETE ON public.organizations
FOR EACH ROW EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.organizations ENABLE ALWAYS TRIGGER trg_scope_organizations_v107_row;
CREATE TRIGGER trg_scope_organizations_actual_v107 AFTER UPDATE ON public.organizations
FOR EACH ROW WHEN (OLD.id IS DISTINCT FROM NEW.id OR OLD.enabled IS DISTINCT FROM NEW.enabled OR OLD.auth_version IS DISTINCT FROM NEW.auth_version) EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.organizations ENABLE ALWAYS TRIGGER trg_scope_organizations_actual_v107;
CREATE TRIGGER trg_scope_organizations_v107 AFTER TRUNCATE ON public.organizations FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.organizations ENABLE ALWAYS TRIGGER trg_scope_organizations_v107;
CREATE TRIGGER trg_scope_user_roles_v107_row AFTER INSERT OR UPDATE OR DELETE ON public.user_roles FOR EACH ROW EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.user_roles ENABLE ALWAYS TRIGGER trg_scope_user_roles_v107_row;
CREATE TRIGGER trg_scope_user_roles_v107 AFTER TRUNCATE ON public.user_roles FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.user_roles ENABLE ALWAYS TRIGGER trg_scope_user_roles_v107;
CREATE TRIGGER trg_scope_erasure_review_authorities_v105_v107_row AFTER INSERT OR UPDATE OR DELETE ON public.erasure_review_authorities_v105 FOR EACH ROW EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.erasure_review_authorities_v105 ENABLE ALWAYS TRIGGER trg_scope_erasure_review_authorities_v105_v107_row;
CREATE TRIGGER trg_scope_erasure_review_authorities_v105_v107 AFTER TRUNCATE ON public.erasure_review_authorities_v105 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.erasure_review_authorities_v105 ENABLE ALWAYS TRIGGER trg_scope_erasure_review_authorities_v105_v107;
CREATE TRIGGER trg_scope_chat_retention_holds_v86_v107_row AFTER INSERT OR UPDATE OR DELETE ON public.chat_retention_holds_v86 FOR EACH ROW EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.chat_retention_holds_v86 ENABLE ALWAYS TRIGGER trg_scope_chat_retention_holds_v86_v107_row;
CREATE TRIGGER trg_scope_chat_retention_holds_v86_v107 AFTER TRUNCATE ON public.chat_retention_holds_v86 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.chat_retention_holds_v86 ENABLE ALWAYS TRIGGER trg_scope_chat_retention_holds_v86_v107;
CREATE TRIGGER trg_scope_erasure_review_holds_v105_v107_row AFTER INSERT OR UPDATE OR DELETE ON public.erasure_review_holds_v105 FOR EACH ROW EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.erasure_review_holds_v105 ENABLE ALWAYS TRIGGER trg_scope_erasure_review_holds_v105_v107_row;
CREATE TRIGGER trg_scope_erasure_review_holds_v105_v107 AFTER TRUNCATE ON public.erasure_review_holds_v105 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.erasure_review_holds_v105 ENABLE ALWAYS TRIGGER trg_scope_erasure_review_holds_v105_v107;
CREATE TRIGGER trg_scope_roles_v107 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.roles FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.roles ENABLE ALWAYS TRIGGER trg_scope_roles_v107;
CREATE TRIGGER trg_scope_platform_erasure_policy_v102_v107 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.platform_erasure_policy_v102 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.platform_erasure_policy_v102 ENABLE ALWAYS TRIGGER trg_scope_platform_erasure_policy_v102_v107;
CREATE TRIGGER trg_scope_erasure_review_actor_keys_v105_v107 AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.erasure_review_actor_keys_v105 FOR EACH STATEMENT EXECUTE FUNCTION public.bump_scoped_epochs_v107();
ALTER TABLE public.erasure_review_actor_keys_v105 ENABLE ALWAYS TRIGGER trg_scope_erasure_review_actor_keys_v105_v107;

-- Technical audit only. DB login identifies the connection, not a human.
-- Independent approval/ticket evidence must come from an external trusted issuer.
CREATE TABLE public.erasure_gate_audit_v107 (
    event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type text NOT NULL CHECK (event_type IN ('CUTOVER_SEALED','CANDIDATE_REVOKED')),
    subject_id uuid,
    database_login name NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    transaction_id bigint NOT NULL,
    CHECK ((event_type='CUTOVER_SEALED')=(subject_id IS NULL))
);
CREATE FUNCTION public.audit_erasure_gate_v107() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
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
$$;
CREATE TRIGGER trg_cutover_audit_v107 AFTER UPDATE ON public.chat_execution_cutover_v106
FOR EACH ROW EXECUTE FUNCTION public.audit_erasure_gate_v107();
CREATE TRIGGER trg_permit_revoke_audit_v107 AFTER UPDATE ON public.erasure_execution_permits_v106
FOR EACH ROW EXECUTE FUNCTION public.audit_erasure_gate_v107();
ALTER TABLE public.chat_execution_cutover_v106 ENABLE ALWAYS TRIGGER trg_cutover_audit_v107;
ALTER TABLE public.erasure_execution_permits_v106 ENABLE ALWAYS TRIGGER trg_permit_revoke_audit_v107;
CREATE FUNCTION public.reject_gate_evidence_change_v107() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_EVIDENCE_IMMUTABLE';
END;
$$;
CREATE FUNCTION public.guard_gate_audit_insert_v107() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF pg_trigger_depth() < 2 OR NEW.database_login IS DISTINCT FROM session_user
       OR NEW.transaction_id IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V107_AUDIT_INSERT_REQUIRES_TRIGGER';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_gate_audit_insert_v107 BEFORE INSERT ON public.erasure_gate_audit_v107
FOR EACH ROW EXECUTE FUNCTION public.guard_gate_audit_insert_v107();
ALTER TABLE public.erasure_gate_audit_v107 ENABLE ALWAYS TRIGGER trg_gate_audit_insert_v107;
CREATE TRIGGER trg_gate_audit_immutable_v107 BEFORE UPDATE OR DELETE ON public.erasure_gate_audit_v107
FOR EACH ROW EXECUTE FUNCTION public.reject_gate_evidence_change_v107();
CREATE TRIGGER trg_gate_audit_no_truncate_v107 BEFORE TRUNCATE ON public.erasure_gate_audit_v107
FOR EACH STATEMENT EXECUTE FUNCTION public.reject_gate_evidence_change_v107();
ALTER TABLE public.erasure_gate_audit_v107 ENABLE ALWAYS TRIGGER trg_gate_audit_immutable_v107;
ALTER TABLE public.erasure_gate_audit_v107 ENABLE ALWAYS TRIGGER trg_gate_audit_no_truncate_v107;

-- Catalog definitions snapshot for drift detection after V107 installation.
-- Trust boundary: migrator/DBA and first V107 deployment; NOT hostile owner.
CREATE TABLE public.erasure_gate_catalog_v107 (
    object_kind text NOT NULL CHECK (object_kind IN ('TRIGGER','FUNCTION')),
    object_name text NOT NULL,
    digest text NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(object_kind,object_name)
);
CREATE TRIGGER trg_gate_catalog_immutable_v107 BEFORE UPDATE OR DELETE ON public.erasure_gate_catalog_v107
FOR EACH ROW EXECUTE FUNCTION public.reject_gate_evidence_change_v107();
CREATE TRIGGER trg_gate_catalog_no_truncate_v107 BEFORE TRUNCATE ON public.erasure_gate_catalog_v107
FOR EACH STATEMENT EXECUTE FUNCTION public.reject_gate_evidence_change_v107();
ALTER TABLE public.erasure_gate_catalog_v107 ENABLE ALWAYS TRIGGER trg_gate_catalog_immutable_v107;
ALTER TABLE public.erasure_gate_catalog_v107 ENABLE ALWAYS TRIGGER trg_gate_catalog_no_truncate_v107;

INSERT INTO public.erasure_gate_catalog_v107(object_kind,object_name,digest)
SELECT 'FUNCTION'::text AS object_kind,p.oid::regprocedure::text AS object_name,
       encode(sha256(convert_to(pg_get_functiondef(p.oid),'UTF8')),'hex') AS digest
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='public' AND p.prokind='f' AND p.proname ~ '_v(44|67|86|103|104|105|106|107)$'
UNION ALL
SELECT 'TRIGGER',c.oid::regclass::text||':'||g.tgname,
       encode(sha256(convert_to(pg_get_triggerdef(g.oid,false)||'|'||g.tgenabled::text,'UTF8')),'hex')
FROM pg_trigger g JOIN pg_class c ON c.oid=g.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND NOT g.tgisinternal AND (g.tgname ~ '_v(44|67|86|103|104|105|106|107)(_row)?$' OR g.tgfoid IN
    (SELECT p.oid FROM pg_proc p JOIN pg_namespace pn ON pn.oid=p.pronamespace WHERE pn.nspname='public' AND p.proname ~ '_v(44|67|86|103|104|105|106|107)$'));

REVOKE ALL ON public.erasure_scoped_epochs_v107,public.erasure_gate_audit_v107,public.erasure_gate_catalog_v107 FROM PUBLIC,safeai_app,safeai_chat_erasure_worker,safeai_knowledge_erasure_worker,safeai_chat_erasure_operator,safeai_knowledge_erasure_operator;
REVOKE ALL ON FUNCTION public.check_permit_after_reservation_v107(),public.lock_v105_chat_hold_v107(),public.guard_combined_chat_holds_v107(),public.guard_scoped_epochs_v107(),public.bump_scoped_epochs_v107(),public.audit_erasure_gate_v107(),public.reject_gate_evidence_change_v107(),public.guard_gate_audit_insert_v107() FROM PUBLIC,safeai_app,safeai_chat_erasure_worker,safeai_knowledge_erasure_worker,safeai_chat_erasure_operator,safeai_knowledge_erasure_operator;

-- V107 private ACL and full catalog fingerprint verification.
SET LOCAL search_path=pg_catalog,pg_temp;
DO $v107_acl$
DECLARE r record; t record; f record;
BEGIN
    FOR r IN SELECT name FROM (VALUES ('safeai_app'),('safeai_chat_erasure_worker'),('safeai_knowledge_erasure_worker'),('safeai_chat_erasure_operator'),('safeai_knowledge_erasure_operator')) v(name) LOOP
        FOR t IN SELECT name FROM (VALUES ('erasure_scoped_epochs_v107'),('erasure_gate_audit_v107'),('erasure_gate_catalog_v107')) v(name) LOOP
            IF has_table_privilege(r.name,format('public.%I',t.name),'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
              OR has_any_column_privilege(r.name,format('public.%I',t.name),'SELECT,INSERT,UPDATE,REFERENCES') THEN
                RAISE EXCEPTION 'V107 private relation grant: % %',r.name,t.name;
            END IF;
        END LOOP;
        FOR f IN SELECT name FROM (VALUES ('check_permit_after_reservation_v107'),('lock_v105_chat_hold_v107'),('guard_combined_chat_holds_v107'),('guard_scoped_epochs_v107'),('bump_scoped_epochs_v107'),('audit_erasure_gate_v107'),('reject_gate_evidence_change_v107'),('guard_gate_audit_insert_v107')) v(name) LOOP
            IF has_function_privilege(r.name,format('public.%I()',f.name),'EXECUTE') THEN
                RAISE EXCEPTION 'V107 private routine grant: % %',r.name,f.name;
            END IF;
        END LOOP;
    END LOOP;
    FOR t IN SELECT name FROM (VALUES ('erasure_scoped_epochs_v107'),('erasure_gate_audit_v107'),('erasure_gate_catalog_v107')) v(name) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid=format('public.%I',t.name)::regclass AND c.relowner=current_user::regrole)
          OR EXISTS (SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=format('public.%I',t.name)::regclass AND a.grantee=0)
          OR EXISTS (SELECT 1 FROM pg_attribute c CROSS JOIN LATERAL aclexplode(c.attacl) a WHERE c.attrelid=format('public.%I',t.name)::regclass AND a.grantee=0) THEN
            RAISE EXCEPTION 'V107 private relation ownership or PUBLIC grant: %',t.name;
        END IF;
    END LOOP;
    FOR f IN SELECT name,definer FROM (VALUES ('check_permit_after_reservation_v107',false),('lock_v105_chat_hold_v107',true),('guard_combined_chat_holds_v107',true),('guard_scoped_epochs_v107',false),('bump_scoped_epochs_v107',true),('audit_erasure_gate_v107',false),('reject_gate_evidence_change_v107',false),('guard_gate_audit_insert_v107',false)) v(name,definer) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid=format('public.%I()',f.name)::regprocedure AND p.proowner=current_user::regrole AND p.prosecdef=f.definer)
          OR EXISTS (SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.oid=format('public.%I()',f.name)::regprocedure AND a.grantee=0) THEN
            RAISE EXCEPTION 'V107 private routine ownership or PUBLIC grant: %',f.name;
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
$v107_acl$;
