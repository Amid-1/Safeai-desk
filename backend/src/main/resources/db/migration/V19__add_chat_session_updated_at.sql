/*backend/src/main/resources/db/migration/V19__add_chat_session_updated_at.sql*/
alter table chat_sessions
    add column if not exists updated_at timestamp not null default now();

create index if not exists idx_chat_sessions_user_id_updated_at
    on chat_sessions(user_id, updated_at desc);