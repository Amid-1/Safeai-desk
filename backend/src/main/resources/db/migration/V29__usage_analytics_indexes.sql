/* Safeai-desk/backend/src/main/resources/db/migration/V29__usage_analytics_indexes.sql */
-- PostgreSQL executes CREATE INDEX CONCURRENTLY outside a transaction.

create index concurrently if not exists
    idx_chat_messages_usage_live_org_date_model
    on chat_messages (organization_id, created_at, model)
    where role = 'ASSISTANT';

create index concurrently if not exists
    idx_chat_messages_usage_live_date_model
    on chat_messages (created_at, model)
    where role = 'ASSISTANT';

create index concurrently if not exists
    idx_usage_daily_quality_org_date
    on usage_daily_quality_rollups (
        organization_id,
        usage_date desc
    );

create index concurrently if not exists
    idx_usage_daily_quality_date
    on usage_daily_quality_rollups (
        usage_date desc
    );
