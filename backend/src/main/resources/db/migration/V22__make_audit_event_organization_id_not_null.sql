/*backend/src/main/resources/db/migration/V22__make_audit_event_organization_id_not_null.sql*/
alter table audit_events
    alter column organization_id set not null;