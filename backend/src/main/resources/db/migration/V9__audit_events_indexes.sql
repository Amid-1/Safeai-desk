/* Safeai-desk/backend/src/main/resources/db/migration/V9__audit_events_indexes.sql */
create index idx_audit_events_org_type_created_at
    on audit_events (organization_id, event_type, created_at desc);