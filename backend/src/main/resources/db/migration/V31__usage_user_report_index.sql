/* Safeai-desk/backend/src/main/resources/db/migration/V31__usage_user_report_index.sql */
-- Additional live-usage index for user-scoped reports.
-- PostgreSQL executes CREATE INDEX CONCURRENTLY outside a transaction.

create index concurrently if not exists
    idx_chat_messages_usage_live_session_date_model
    on chat_messages (session_id, created_at, model)
    where role = 'ASSISTANT';
