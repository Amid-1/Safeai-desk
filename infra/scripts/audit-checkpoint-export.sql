-- SafeAI-Desk/infra/scripts/audit-checkpoint-export.sql
-- Format v1: PostgreSQL 16 JSONB text, UTF-8, UTC timestamp; never edit in place.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path = pg_catalog;
SET LOCAL row_security = off;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL DateStyle = 'ISO, YMD';
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

DO $guard$
BEGIN
    IF current_user <> 'safeai_audit_verifier'
        OR session_user <> current_user
        OR EXISTS (SELECT FROM pg_roles WHERE rolname = current_user
                   AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication))
        OR EXISTS (SELECT FROM pg_auth_members WHERE member = current_user::regrole)
        OR has_schema_privilege(current_user, 'public', 'CREATE')
        OR has_table_privilege(current_user, 'public.audit_events',
                               'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR has_any_column_privilege(current_user, 'public.audit_events',
                                    'INSERT,UPDATE,REFERENCES')
        OR pg_has_role(current_user, (SELECT relowner FROM pg_class
                    WHERE oid = 'public.audit_events'::regclass), 'MEMBER')
        OR NOT has_table_privilege(current_user, 'public.audit_events', 'SELECT')
        OR current_setting('server_version_num')::integer / 10000 <> 16
        OR current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'Unsafe audit checkpoint reader or incompatible PostgreSQL format';
    END IF;
    IF (SELECT array_agg(attname::text ORDER BY attname::text COLLATE "C")
        FROM pg_attribute WHERE attrelid = 'public.audit_events'::regclass
          AND attnum > 0 AND NOT attisdropped) IS DISTINCT FROM ARRAY[
        'actor_display_name','actor_email','actor_organization_id','actor_user_id',
        'correlation_id','created_at','details','event_type','id','organization_id',
        'target_organization_name']::text[] THEN
        RAISE EXCEPTION 'Audit evidence schema changed; review checkpoint format';
    END IF;
END
$guard$;

COPY (
    SELECT id::text, jsonb_build_array(
        id, actor_user_id, actor_organization_id, actor_email, actor_display_name,
        organization_id, target_organization_name, event_type, details,
        created_at AT TIME ZONE 'UTC', correlation_id
    )::text
    FROM public.audit_events
    ORDER BY audit_events.id
) TO STDOUT WITH (FORMAT csv, FORCE_QUOTE *);
COPY (SELECT 'END', count(*)::text FROM public.audit_events)
    TO STDOUT WITH (FORMAT csv, FORCE_QUOTE *);
COMMIT;
