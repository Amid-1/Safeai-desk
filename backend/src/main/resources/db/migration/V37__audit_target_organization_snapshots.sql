/* Safeai-desk/backend/src/main/resources/db/migration/V37__audit_target_organization_snapshots.sql */
/*
 * SafeAI Desk — immutable target organization snapshots for audit.
 *
 * Latest migration version confirmed in the supplied project evidence: V36.
 * IMPORTANT:
 * - Check the current migration directory before merging this patch.
 * - If V37 is already occupied in your checkout, rename THIS NEW migration
 *   to the next unused version BEFORE it is applied anywhere.
 * - Never edit an already-applied migration to fit this patch.
 */

alter table public.audit_events
    add column if not exists target_organization_name varchar(255);

alter table public.audit_outbox
    add column if not exists target_organization_name varchar(255);

/*
 * Legacy backfill. This freezes the CURRENT organization name for old rows;
 * historical names that changed before this migration cannot be reconstructed.
 * New rows are true event-time immutable snapshots captured at outbox enqueue.
 *
 * On a very large audit table, schedule this migration in a maintenance window
 * because the update can generate substantial WAL even though it only touches
 * rows whose snapshot is currently null.
 */
update public.audit_events as audit_event
set target_organization_name = organization.name
from public.organizations as organization
where organization.id = audit_event.organization_id
  and audit_event.target_organization_name is null;

update public.audit_outbox as outbox
set target_organization_name = organization.name
from public.organizations as organization
where organization.id = outbox.organization_id
  and outbox.target_organization_name is null;

alter table public.audit_events
    drop constraint if exists chk_audit_events_target_organization_name;

alter table public.audit_events
    add constraint chk_audit_events_target_organization_name
        check (
            target_organization_name is null
            or length(btrim(target_organization_name)) between 1 and 255
        ) not valid;

alter table public.audit_events
    validate constraint chk_audit_events_target_organization_name;

alter table public.audit_outbox
    drop constraint if exists chk_audit_outbox_target_organization_name;

alter table public.audit_outbox
    add constraint chk_audit_outbox_target_organization_name
        check (
            target_organization_name is null
            or length(btrim(target_organization_name)) between 1 and 255
        ) not valid;

alter table public.audit_outbox
    validate constraint chk_audit_outbox_target_organization_name;

/*
 * No new generic audit/outbox indexes are created here intentionally.
 * The project already has dedicated audit query/retention index migrations.
 * Add another index only after EXPLAIN (ANALYZE, BUFFERS) proves a missing
 * access path in the actual production workload; duplicate btrees increase
 * write amplification, vacuum cost and disk usage.
 */
