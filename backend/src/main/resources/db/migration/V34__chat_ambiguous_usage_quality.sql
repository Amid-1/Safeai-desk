/* Safeai-desk/backend/src/main/resources/db/migration/V34__chat_ambiguous_usage_quality.sql */
-- Adds ambiguous provider operations to usage data-quality rollups.
-- Requires the production usage module (V28-V31).

alter table usage_daily_quality_rollups
    add column ambiguous_provider_operation_count bigint not null default 0;

alter table usage_daily_quality_rollups
    add constraint chk_usage_quality_ambiguous_non_negative
        check (ambiguous_provider_operation_count >= 0);

comment on column usage_daily_quality_rollups.ambiguous_provider_operation_count is
    'Provider operations with unknown outcome/cost; sourced from chat_turns.AMBIGUOUS.';
