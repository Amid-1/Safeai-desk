/* Safeai-desk/backend/src/main/resources/db/migration/V10__schema_hardening_timestamps_audit_rollups.sql */

-- 1. Defaults for base timestamps.
-- This reduces risk for raw SQL inserts and future code paths.
alter table organizations
    alter column created_at set default now(),
    alter column updated_at set default now();

alter table users
    alter column created_at set default now(),
    alter column updated_at set default now();

alter table chat_sessions
    alter column created_at set default now(),
    alter column updated_at set default now();

alter table chat_messages
    alter column created_at set default now();

alter table refresh_tokens
    alter column created_at set default now();

alter table audit_events
    alter column created_at set default now();


-- 2. Defaults for V7 quota and rollup tables.
alter table organization_ai_quotas
    alter column created_at set default now(),
    alter column updated_at set default now();

alter table user_ai_quotas
    alter column created_at set default now(),
    alter column updated_at set default now();

alter table usage_daily_org_model_rollups
    alter column created_at set default now(),
    alter column updated_at set default now();

alter table usage_daily_user_model_rollups
    alter column created_at set default now(),
    alter column updated_at set default now();


-- 3. Normalize existing rollup rows before adding strict constraints.
update usage_daily_org_model_rollups
set total_tokens = input_tokens + output_tokens
where total_tokens <> input_tokens + output_tokens;

update usage_daily_user_model_rollups
set total_tokens = input_tokens + output_tokens
where total_tokens <> input_tokens + output_tokens;


-- 4. Enforce total_tokens consistency.
alter table usage_daily_org_model_rollups
    add constraint chk_usage_daily_org_model_total_tokens_match
        check (total_tokens = input_tokens + output_tokens);

alter table usage_daily_user_model_rollups
    add constraint chk_usage_daily_user_model_total_tokens_match
        check (total_tokens = input_tokens + output_tokens);


-- 5. Make audit details consistently non-null.
update audit_events
set details = '{}'::jsonb
where details is null;

alter table audit_events
    alter column details set default '{}'::jsonb,
    alter column details set not null;


-- 6. Composite audit index for common ADMIN/SUPER_ADMIN filtering.
create index if not exists idx_audit_events_org_type_created_at
    on audit_events (organization_id, event_type, created_at desc);