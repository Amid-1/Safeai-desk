/* Safeai-desk/backend/src/main/resources/db/migration/V18__audit_actor_snapshots.sql */
-- V18: immutable actor identity snapshots for audit events.
--
-- actor_user_id deliberately has no foreign key:
-- audit history must survive user deletion and identity changes.

alter table audit_events
    add column actor_user_id uuid,
    add column actor_email varchar(255),
    add column actor_display_name varchar(255);

-- Backfill historical events from the current user data.
-- Для старых событий это не настоящий исторический snapshot,
-- а лучшее доступное значение на момент миграции.
update audit_events ae
set actor_user_id = ae.user_id,
    actor_email = nullif(
            lower(trim(u.email)),
            ''
                  ),
    actor_display_name = nullif(
            trim(u.full_name),
            ''
                         )
    from users u
where u.id = ae.user_id;

-- События, у которых пользователь уже отсутствует,
-- всё равно получают исторический идентификатор,
-- если user_id сохранился.
update audit_events
set actor_user_id = user_id
where actor_user_id is null
  and user_id is not null;

alter table audit_events
    add constraint chk_audit_events_actor_email_not_blank
        check (
            actor_email is null
                or length(trim(actor_email)) > 0
            ),

    add constraint chk_audit_events_actor_display_name_not_blank
        check (
            actor_display_name is null
                or length(trim(actor_display_name)) > 0
        ),

    add constraint chk_audit_events_actor_email_canonical
        check (
            actor_email is null
                or actor_email = lower(trim(actor_email))
        );

create index idx_audit_events_actor_user_created_at
    on audit_events (
                     actor_user_id,
                     created_at desc
        )
    where actor_user_id is not null;

create index idx_audit_events_actor_email_created_at
    on audit_events (
                     actor_email,
                     created_at desc
        )
    where actor_email is not null;

create index idx_audit_events_org_actor_user_created_at
    on audit_events (
                     organization_id,
                     actor_user_id,
                     created_at desc
        )
    where actor_user_id is not null;