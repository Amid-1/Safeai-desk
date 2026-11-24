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
('chat_erasure_operations_v86','SI','',''),
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
    IF (SELECT count(*) FROM acl_matrix) <> 70 OR EXISTS (
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
    LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', r.signature);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO safeai_app,safeai_chat_erasure_worker,safeai_knowledge_erasure_worker',r.signature);
    END LOOP;
END $routines$;
GRANT EXECUTE ON FUNCTION public.gc_knowledge_generation_chunks_v90(uuid,uuid) TO safeai_app;

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
        WHERE n.nspname='public' AND p.prosecdef) <> 6 THEN
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
                IS DISTINCT FROM (f.proname='gc_knowledge_generation_chunks_v90') THEN
            RAISE EXCEPTION 'Unsafe definer function: %',f.proname;
        END IF;
        FOREACH worker IN ARRAY ARRAY['safeai_chat_erasure_worker','safeai_knowledge_erasure_worker'] LOOP
            IF has_function_privilege(worker,f.oid,'EXECUTE') THEN
                RAISE EXCEPTION 'Unexpected worker definer EXECUTE: % %',worker,f.proname;
            END IF;
        END LOOP;
    END LOOP;
    IF NOT has_function_privilege('safeai_app','public.gc_knowledge_generation_chunks_v90(uuid,uuid)','EXECUTE')
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
SELECT * FROM acl_matrix ORDER BY name;
