/* Safeai-desk/backend/src/main/resources/db/migration/V8__add_audit_event_type_index.sql */
create index idx_audit_events_event_type_created_at
    on audit_events (event_type, created_at desc);