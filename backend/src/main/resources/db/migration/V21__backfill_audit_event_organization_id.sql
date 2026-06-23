/*backend/src/main/resources/db/migration/V21__backfill_audit_event_organization_id.sql*/
update audit_events ae
set organization_id = u.organization_id
from users u
where ae.user_id = u.id
  and ae.organization_id is null;

update audit_events
set organization_id = '00000000-0000-0000-0000-000000000001'
where organization_id is null;