/*backend/src/main/resources/db/migration/V14__add_audit_event_organization_id.sql*/
alter table audit_events
    add column if not exists organization_id uuid references organizations(id);

create index if not exists idx_audit_events_organization_id_created_at
    on audit_events (organization_id, created_at desc);