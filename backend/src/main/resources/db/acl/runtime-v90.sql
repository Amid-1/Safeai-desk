-- V1-V93 reviewed: V93 adds one short-lived confirmation relation, with column-only writes.
-- S=SELECT I=INSERT U=UPDATE D=DELETE. '-' means no table-level grants.
-- Last two fields are comma-separated column-only INSERT / UPDATE.
CREATE TEMP TABLE acl_matrix (
    name text PRIMARY KEY, perms text NOT NULL,
    insert_cols text NOT NULL, update_cols text NOT NULL
) ON COMMIT DROP;
INSERT INTO acl_matrix VALUES
('audit_event_types','-','',''),
('audit_event_user_links','I','',''),
('audit_events','SID','',''),
('audit_outbox','SIUD','',''),
('erasure_previews_v103','SI','',''),
('erasure_requests_v103','SI','','state,version,updated_at,submitted_at,submit_key,withdraw_key'),
('erasure_request_events_v103','SI','',''),
('erasure_review_authorities_v105','S','',''),
('erasure_review_actor_keys_v105','-','',''),
('erasure_reviews_v105','S','','review_state,review_version,hold_revision,updated_by_user_id,last_event_code,last_key,reason_code,updated_at'),
('erasure_review_events_v105','S','',''),
('erasure_review_holds_v105','SI','','released_at,released_by_user_id,release_key'),
('chat_erasure_operations_v86','SI','',''),
('chat_execution_cutover_v106','-','',''),
('erasure_execution_epochs_v106','-','',''),
('erasure_execution_permits_v106','-','',''),
('erasure_execution_identifiers_v106','-','',''),
('erasure_scoped_epochs_v107','-','',''),
('erasure_gate_audit_v107','-','',''),
('erasure_gate_catalog_v107','-','',''),
('chat_erasure_operator_approvals_v86','-','',''),
('chat_messages','SI','',''),
('chat_physical_spend_v67','S','',''),
('chat_quota_calendar_v96','S','',''),
('chat_quota_reservations','SIU','',''),
('chat_retention_holds_v86','S','',''),
('chat_sessions','SIU','',''),
('chat_turns','SIU','',''),
('flyway_schema_history','-','',''),
('knowledge_answer_citations','SI','',''),
('knowledge_answer_passports','SI','',''),
('knowledge_base_deletions_v82','S','id,organization_id,knowledge_base_id,requested_by_user_id,expected_version,idempotency_key','state,next_attempt_at,lease_token,lease_until,updated_at'),
('knowledge_base_identities_v82','I','',''),
('knowledge_base_memberships','SIUD','',''),
('knowledge_bases','SIU','',''),
('knowledge_chunk_identities_v80','I','',''),
('knowledge_document_chunks','SI','',''),
('knowledge_document_deletions_v78','S','id,organization_id,knowledge_base_id,document_id,requested_by_user_id,idempotency_key,expected_version','state,next_attempt_at,lease_token,lease_until,updated_at'),
('knowledge_document_erasure_objects_v81','S','',''),
('knowledge_document_version_identities_v80','I','',''),
('knowledge_document_versions','SI','','id'),
('knowledge_documents','SIU','',''),
('knowledge_erasure_commit_outbox_v89','S','',''),
('knowledge_evaluation_cases','SI','',''),
('knowledge_evaluation_runs','SI','',''),
('knowledge_index_generations','SIU','',''),
('knowledge_ingestion_jobs','SIU','',''),
('knowledge_rag_context_receipts_v64','SI','',''),
('knowledge_rag_execution_attempt_leases_v85','SIU','',''),
('knowledge_rag_execution_permit_sources_v85','SI','',''),
('knowledge_rag_execution_permits_v85','SIU','',''),
('knowledge_retention_recovery_reviews_v89','-','',''),
('knowledge_retrieval_hits','SI','',''),
('knowledge_retrieval_runs','SI','',''),
('knowledge_storage_upload_intents','SIU','',''),
('model_catalog_entries','SI','',''),
('model_cost_commitment_ledger_v60','-','',''),
('model_cost_commitment_ledger_v62','S','',''),
('model_execution_attempts','SIU','',''),
('model_execution_plans','SI','','id'),
('model_physical_dispatch_receipts_v100','S','attempt_id',''),
('model_policy_preview_confirmations','SD','organization_id,actor_id,token_hash,draft_hash,context_hash,lockout,expires_at','token_hash,draft_hash,context_hash,lockout,expires_at,result_policy_id'),
('model_execution_reconciliation_queue_v54','-','',''),
('model_route_budget_commitments_v64','SI','',''),
('model_route_decisions','SI','',''),
('model_route_prepared_requests_v62','SI','',''),
('model_route_reserved_requests','SI','',''),
('organization_ai_quotas','S','','updated_at'),
('organization_model_policies','SI','',''),
('organizations','SIU','',''),
('platform_erasure_policy_v102','S','','chat_allowed,knowledge_allowed,version'),
('refresh_tokens','SIUD','',''),
('roles','S','',''),
('usage_daily_org_model_rollups','SID','',''),
('usage_daily_quality_rollups','SIUD','',''),
('usage_daily_user_model_rollups','SID','',''),
('usage_dirty_dates','SIUD','',''),
('usage_export_jobs','SD','id,owner_id,owner_org,token_version,org_version,owner_role,kind,date_from,date_to,organization_id,user_id,model,report_order,budget_status,created_at,expires_at','state,attempts,lease_token,lease_until,csv,failure'),
('usage_rollup_state','SIU','',''),
('user_ai_quotas','S','','updated_at'),
('user_roles','SID','','user_id'),
('users','SIUD','','');
DO $check$
DECLARE r record; col text;
BEGIN
    IF current_user <> 'safeai_migrator' OR session_user <> current_user
        OR has_schema_privilege('safeai_app','public','CREATE')
        OR has_database_privilege('safeai_app',current_database(),'TEMP')
        OR EXISTS (SELECT 1 FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) a
                   WHERE a.grantee='safeai_app'::regrole)
        OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                   WHERE n.nspname='public' AND c.relkind='S') THEN
        RAISE EXCEPTION 'Unsafe default ACL, runtime capability or inventory';
    END IF;
    IF (SELECT count(*) FROM acl_matrix) <> 82 OR EXISTS (
        SELECT name FROM acl_matrix EXCEPT
        SELECT c.relname::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','f')
    ) OR EXISTS (
        SELECT c.relname::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','f')
        EXCEPT SELECT name FROM acl_matrix
    ) THEN
        RAISE EXCEPTION 'Unexpected public relation inventory';
    END IF;
    FOR r IN SELECT * FROM acl_matrix LOOP
        IF (SELECT relowner FROM pg_class WHERE oid=format('public.%I',r.name)::regclass)
            <> 'safeai_migrator'::regrole THEN
            RAISE EXCEPTION 'Unexpected owner: %',r.name;
        END IF;
        FOREACH col IN ARRAY string_to_array(concat_ws(',',nullif(r.insert_cols,''),
            nullif(r.update_cols,'')),',') LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_attribute
                WHERE attrelid=format('public.%I',r.name)::regclass
                    AND attname=col AND attnum>0 AND NOT attisdropped) THEN
                RAISE EXCEPTION 'Missing column %.%',r.name,col;
            END IF;
        END LOOP;
    END LOOP;
END;
$check$;

-- One effective ACL checker, called before and after the grants.
CREATE FUNCTION pg_temp.check_acl(post_grant boolean) RETURNS void
LANGUAGE plpgsql AS $fn$
DECLARE r record; p record; a record; wanted text; expect boolean; cols text;
BEGIN
    FOR r IN SELECT *,format('public.%I',name)::regclass AS oid FROM acl_matrix LOOP
        wanted := CASE WHEN post_grant THEN r.perms
            WHEN r.name='chat_erasure_operations_v86' THEN 'SI'
            WHEN r.name IN ('chat_retention_holds_v86','knowledge_erasure_commit_outbox_v89') THEN 'S'
            ELSE '-' END;
        FOR p IN SELECT * FROM (VALUES ('S','SELECT'),('I','INSERT'),('U','UPDATE'),
            ('D','DELETE'),('T','TRUNCATE'),('R','REFERENCES'),('G','TRIGGER')) v(code,priv)
        LOOP
            expect := strpos(wanted,p.code)>0;
            IF has_table_privilege('safeai_app',r.oid,p.priv) IS DISTINCT FROM expect
                OR has_table_privilege('safeai_app',r.oid,p.priv||' WITH GRANT OPTION') THEN
                RAISE EXCEPTION 'Unexpected table ACL: % % (expected %)',r.name,p.priv,expect;
            END IF;
            IF p.code IN ('S','I','U','R') THEN
                cols := CASE WHEN post_grant AND p.code='I' THEN r.insert_cols
                    WHEN post_grant AND p.code='U' THEN r.update_cols ELSE '' END;
                FOR a IN SELECT attnum,attname FROM pg_attribute
                    WHERE attrelid=r.oid AND attnum>0 AND NOT attisdropped
                LOOP
                    IF has_column_privilege('safeai_app',r.oid,a.attnum,p.priv)
                        IS DISTINCT FROM (expect OR a.attname::text=ANY(string_to_array(cols,',')))
                        OR has_column_privilege('safeai_app',r.oid,a.attnum,p.priv||' WITH GRANT OPTION') THEN
                        RAISE EXCEPTION 'Unexpected column ACL: %.% %',r.name,a.attname,p.priv;
                    END IF;
                END LOOP;
            END IF;
        END LOOP;
    END LOOP;
END;
$fn$;

-- All application-owned invoker routines are reviewed in the immutable V1–V100
-- history; extension functions are granted separately by bootstrap.
-- SECURITY DEFINER functions retain only their migration-specific grants.
DO $routines$
DECLARE r record;
BEGIN
    FOR r IN SELECT p.oid::regprocedure AS signature FROM pg_proc p
        JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='public' AND p.proowner=current_user::regrole
          AND NOT p.prosecdef AND p.prokind='f'
          AND p.proname NOT IN ('guard_chat_execution_cutover_v106','guard_chat_execution_intent_v106','guard_erasure_epochs_v106','bump_erasure_epochs_v106','guard_erasure_permit_v106','reserve_erasure_identifiers_v106','guard_erasure_identifiers_v106','check_permit_after_reservation_v107','lock_v105_chat_hold_v107','guard_combined_chat_holds_v107','guard_scoped_epochs_v107','bump_scoped_epochs_v107','audit_erasure_gate_v107','reject_gate_evidence_change_v107','guard_gate_audit_insert_v107','guard_erasure_review_v105','guard_erasure_hold_v105','guard_erasure_request_fence_v108','lock_erasure_hold_case_v108','guard_erasure_review_reason_v108')
    LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', r.signature);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO safeai_app,safeai_chat_erasure_worker,safeai_knowledge_erasure_worker',r.signature);
    END LOOP;
END $routines$;
GRANT EXECUTE ON FUNCTION public.gc_knowledge_generation_chunks_v90(uuid,uuid) TO safeai_app;
-- Bootstrap deliberately revokes routine EXECUTE on every deployment.
-- Restore only the reviewed V105 actor-proof verifier; no worker or PUBLIC grant.
GRANT EXECUTE ON FUNCTION public.assert_erasure_review_actor_v105(uuid,uuid,uuid,text,bigint,text) TO safeai_app;

DO $grant$
DECLARE r record; p record; col text;
BEGIN
    FOR r IN SELECT * FROM acl_matrix ORDER BY name LOOP
        FOR p IN SELECT * FROM (VALUES ('S','SELECT'),('I','INSERT'),('U','UPDATE'),('D','DELETE')) v(code,priv)
        LOOP
            IF strpos(r.perms,p.code)>0 THEN
                EXECUTE format('GRANT %s ON TABLE public.%I TO safeai_app',p.priv,r.name);
            END IF;
        END LOOP;
        FOREACH col IN ARRAY string_to_array(r.insert_cols,',') LOOP
            EXECUTE format('GRANT INSERT (%I) ON TABLE public.%I TO safeai_app',col,r.name);
        END LOOP;
        FOREACH col IN ARRAY string_to_array(r.update_cols,',') LOOP
            EXECUTE format('GRANT UPDATE (%I) ON TABLE public.%I TO safeai_app',col,r.name);
        END LOOP;
    END LOOP;
END;
$grant$;
SELECT pg_temp.check_acl(true);

DO $critical$
DECLARE f record; worker text;
BEGIN
    IF (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='public' AND p.prosecdef) <> 16 THEN
        RAISE EXCEPTION 'Unexpected SECURITY DEFINER inventory';
    END IF;
    FOR f IN SELECT p.oid,p.proname,p.proowner FROM pg_proc p
        JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.prosecdef
    LOOP
        IF f.proowner <> 'safeai_migrator'::regrole
            OR EXISTS (SELECT 1 FROM aclexplode(
              coalesce((SELECT proacl FROM pg_proc WHERE oid=f.oid),acldefault('f',f.proowner))) a
                       WHERE a.grantee=0 AND a.privilege_type='EXECUTE')
            OR has_function_privilege('safeai_app',f.oid,'EXECUTE')
                IS DISTINCT FROM (f.proname IN ('gc_knowledge_generation_chunks_v90',
                    'assert_erasure_review_actor_v105')) THEN
            RAISE EXCEPTION 'Unsafe definer function: %',f.proname;
        END IF;
        FOREACH worker IN ARRAY ARRAY['safeai_chat_erasure_worker','safeai_knowledge_erasure_worker'] LOOP
            IF has_function_privilege(worker,f.oid,'EXECUTE') THEN
                RAISE EXCEPTION 'Unexpected worker definer EXECUTE: % %',worker,f.proname;
            END IF;
        END LOOP;
    END LOOP;
    IF has_table_privilege('safeai_app','public.erasure_review_actor_keys_v105','SELECT')
        OR has_table_privilege('safeai_app','public.erasure_review_events_v105','INSERT')
        OR NOT has_function_privilege('safeai_app',
            'public.assert_erasure_review_actor_v105(uuid,uuid,uuid,text,bigint,text)','EXECUTE')
        OR NOT has_function_privilege('safeai_app','public.gc_knowledge_generation_chunks_v90(uuid,uuid)','EXECUTE')
        OR NOT has_column_privilege('safeai_chat_erasure_worker','public.chat_messages','content','UPDATE')
        OR NOT has_column_privilege('safeai_knowledge_erasure_worker','public.knowledge_bases','lifecycle_state','UPDATE')
        OR NOT has_table_privilege('safeai_knowledge_erasure_worker','public.knowledge_document_chunks','DELETE')
        OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE
            tgrelid='public.knowledge_document_chunks'::regclass
            AND tgname='trg_chunk_gc_guard_v57' AND NOT tgisinternal AND tgenabled IN ('O','A')) THEN
        RAISE EXCEPTION 'Critical worker or GC permissions missing';
    END IF;
END;
$critical$;
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

-- V107 private ACL and full catalog fingerprint verification (V108 approved definer delta).
SET LOCAL search_path=pg_catalog,pg_temp;
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
$v107_acl$;


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

SELECT * FROM acl_matrix ORDER BY name;
