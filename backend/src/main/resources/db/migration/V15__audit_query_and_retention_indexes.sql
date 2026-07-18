/* Safeai-desk/backend/src/main/resources/db/migration/V15__audit_query_and_retention_indexes.sql */
create index if not exists idx_audit_events_created_at_id
    on audit_events (created_at, id);

create index if not exists idx_users_email_lower_pattern
    on users (lower(email) text_pattern_ops);
