/*Safeai-desk/backend/src/main/resources/db/migration/V4__use_timestamptz_for_created_at.sql*/
alter table organizations
alter column created_at type timestamptz
    using created_at at time zone 'UTC';

alter table users
alter column created_at type timestamptz
    using created_at at time zone 'UTC';

alter table chat_sessions
alter column created_at type timestamptz
    using created_at at time zone 'UTC';

alter table chat_messages
alter column created_at type timestamptz
    using created_at at time zone 'UTC';

alter table audit_events
alter column created_at type timestamptz
    using created_at at time zone 'UTC';