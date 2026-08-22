alter table chat_sessions
    add column archived_at timestamptz,
    add column archived_by_user_id uuid;

alter table chat_sessions
    add constraint chk_chat_sessions_archive_metadata
        check (
            (archived_at is null and archived_by_user_id is null)
            or (archived_at is not null and archived_by_user_id is not null)
        ),
    add constraint chk_chat_sessions_archive_time
        check (archived_at is null or archived_at >= created_at);

comment on column chat_sessions.archived_by_user_id is
    'Actor identity snapshot. Intentionally has no FK so chat provenance survives user lifecycle changes.';

create index ix_chat_sessions_active_owner_updated
    on chat_sessions (organization_id, user_id, updated_at desc, id desc)
    where archived_at is null;

insert into audit_event_types (name, description)
values ('CHAT_ARCHIVED', 'Chat session archived by its owner')
on conflict (name) do nothing;
