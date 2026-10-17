-- backend/src/main/resources/db/migration/V74__usage_midnight_dirty_date_and_physical_indexes.sql
-- V74 — harden UTC-midnight writes and bound physical-attempt reporting cost.
-- Prerequisites: V50, V71, V72. Do not edit already-applied Flyway migrations.
-- THIS FIX INTENTIONALLY MARKS EVEN THE CURRENT UTC DAY DIRTY.
-- A transaction starting before midnight and committing after midnight can no
-- longer evade historical rollup repair, regardless of its statement timestamp.
-- The rollup worker only consumes dates <= its covered closed-day watermark.

CREATE OR REPLACE FUNCTION public.safeai_mark_usage_date_dirty_v71(p_date date)
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
BEGIN
    IF p_date IS NULL THEN
        RETURN;
    END IF;

    INSERT INTO public.usage_dirty_dates (usage_date, revision, updated_at)
    VALUES (p_date, 1, clock_timestamp())
    ON CONFLICT (usage_date) DO UPDATE
       SET revision = public.usage_dirty_dates.revision + 1,
           updated_at = clock_timestamp();
END;
$function$;

COMMENT ON FUNCTION public.safeai_mark_usage_date_dirty_v71(date) IS
    'V74: queues current and historical UTC dates in source transaction; prevents midnight crossing gap. '
    'Do not exclude current days: they may commit only after midnight.';

-- Query is driven by attempt.started_at and joins one immutable plan by PK.
-- Explicitly not a unique index: multiple attempts for the same plan are valid.
CREATE INDEX idx_model_execution_attempts_started_plan_v74
    ON public.model_execution_attempts (started_at, execution_plan_id);

-- Scope via organization is on execution plans; supports tenant-selective plans.
CREATE INDEX idx_model_execution_plans_organization_id_v74
    ON public.model_execution_plans (organization_id, id);
