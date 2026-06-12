/*Safeai-desk/backend/src/main/resources/db/migration/V5__add_indexes.sql*/
create index idx_users_organization_id
    on users (organization_id);

create index idx_user_roles_user_id
    on user_roles (user_id);

create index idx_user_roles_role_id
    on user_roles (role_id);

create index idx_chat_sessions_user_id_created_at
    on chat_sessions (user_id, created_at desc);

create index idx_chat_messages_session_id_created_at
    on chat_messages (session_id, created_at asc);

create index idx_audit_events_created_at
    on audit_events (created_at desc);

create index idx_audit_events_user_id_created_at
    on audit_events (user_id, created_at desc);