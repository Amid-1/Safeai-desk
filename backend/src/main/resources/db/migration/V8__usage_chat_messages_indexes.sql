/* Safeai-desk/backend/src/main/resources/db/migration/V8__usage_chat_messages_indexes.sql */
create index idx_chat_messages_usage_global_completed
    on chat_messages (created_at, model)
    where role = 'ASSISTANT'
        and status = 'COMPLETED'
        and model is not null;

create index idx_chat_messages_usage_org_completed
    on chat_messages (organization_id, created_at, model)
    where role = 'ASSISTANT'
        and status = 'COMPLETED'
        and model is not null;

create index idx_chat_messages_usage_session_completed
    on chat_messages (session_id, created_at, model)
    where role = 'ASSISTANT'
        and status = 'COMPLETED'
        and model is not null;