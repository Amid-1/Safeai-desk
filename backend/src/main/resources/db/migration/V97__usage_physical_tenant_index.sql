-- SafeAI-Desk/backend/src/main/resources/db/migration/V97__usage_physical_tenant_index.sql
-- План → интервал: дополняет индекс V74 с обратным порядком колонок.
-- Не блокируем запись попыток на время построения индекса.
SET lock_timeout = '5s';
SET statement_timeout = '30min';
CREATE INDEX CONCURRENTLY idx_model_execution_attempts_plan_started_v97
    ON public.model_execution_attempts (execution_plan_id, started_at);
RESET statement_timeout;
RESET lock_timeout;
