-- SafeAI-Desk/backend/src/main/resources/db/migration/V95__chat_quota_physical_attempt_gate.sql
-- Независимый барьер физического исполнения. Историю V1–V94 не меняем.
-- NULL у исторического резерва означает отсутствие нового доказательства.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE public.chat_quota_reservations
    ADD COLUMN single_physical_attempt boolean,
    ADD COLUMN physical_period_start timestamptz,
    ADD COLUMN physical_period_end timestamptz,
    ADD CONSTRAINT chk_quota_physical_period_v95 CHECK (
        (physical_period_start IS NULL AND physical_period_end IS NULL) OR (
            physical_period_start IS NOT NULL AND physical_period_end IS NOT NULL
            AND isfinite(physical_period_start) AND isfinite(physical_period_end)
            AND physical_period_start <= created_at
            AND physical_period_end > created_at
            AND extract(day FROM period_start) = 1
            AND physical_period_start BETWEEN
                (period_start::timestamp AT TIME ZONE 'UTC') - interval '18 hours' AND
                (period_start::timestamp AT TIME ZONE 'UTC') + interval '18 hours'
            AND physical_period_end BETWEEN
                ((period_start + interval '1 month') AT TIME ZONE 'UTC') - interval '18 hours' AND
                ((period_start + interval '1 month') AT TIME ZONE 'UTC') + interval '18 hours'
            AND physical_period_end > physical_period_start + interval '27 days'
            AND physical_period_end <= physical_period_start + interval '32 days'
        )
    );

-- Блокируем существующие строки политик в том же порядке, что и ChatQuotaService.
-- Изменение политики после допуска STARTED не отменяет уже начатый вызов.
CREATE FUNCTION public.quota_requires_single_attempt_v95(org uuid, actor uuid)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE
    org_bounded boolean;
    user_bounded boolean;
BEGIN
    SELECT enabled AND (monthly_input_token_limit IS NOT NULL
                        OR monthly_output_token_limit IS NOT NULL
                        OR monthly_cost_limit_usd IS NOT NULL)
      INTO org_bounded FROM public.organization_ai_quotas
     WHERE organization_id = org FOR SHARE;
    SELECT enabled AND (monthly_input_token_limit IS NOT NULL
                        OR monthly_output_token_limit IS NOT NULL
                        OR monthly_cost_limit_usd IS NOT NULL)
      INTO user_bounded FROM public.user_ai_quotas
     WHERE user_id = actor FOR SHARE;
    RETURN coalesce(org_bounded, false) OR coalesce(user_bounded, false);
END;
$$;
REVOKE ALL ON FUNCTION public.quota_requires_single_attempt_v95(uuid, uuid) FROM PUBLIC;

CREATE FUNCTION public.guard_quota_reservation_v95()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Только БД определяет признак ограничения, а не вызывающий код.
        NEW.single_physical_attempt :=
            public.quota_requires_single_attempt_v95(NEW.organization_id, NEW.user_id);
        RETURN NEW;
    END IF;
    IF ROW(NEW.turn_id, NEW.organization_id, NEW.user_id, NEW.period_start,
           NEW.created_at, NEW.reservation_version, NEW.model_route_decision_id,
           NEW.cost_verifiable, NEW.reserved_input_tokens, NEW.reserved_output_tokens,
           NEW.reserved_cost_usd, NEW.single_physical_attempt,
           NEW.physical_period_start, NEW.physical_period_end)
       IS DISTINCT FROM
       ROW(OLD.turn_id, OLD.organization_id, OLD.user_id, OLD.period_start,
           OLD.created_at, OLD.reservation_version, OLD.model_route_decision_id,
           OLD.cost_verifiable, OLD.reserved_input_tokens, OLD.reserved_output_tokens,
           OLD.reserved_cost_usd, OLD.single_physical_attempt,
           OLD.physical_period_start, OLD.physical_period_end) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V95 quota reservation evidence is immutable';
    END IF;
    IF OLD.state <> 'RESERVED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V95 terminal quota reservation is immutable';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_quota_reservation_v95() FROM PUBLIC;
CREATE TRIGGER trg_quota_reservation_v95
    BEFORE INSERT OR UPDATE ON public.chat_quota_reservations
    FOR EACH ROW EXECUTE FUNCTION public.guard_quota_reservation_v95();

-- V53/V59, V65, V67, V72 и V85 продолжают действовать независимо.
-- V95 проверяет именно Chat-квоты, а не заменяет бюджет модели.
CREATE FUNCTION public.guard_physical_quota_attempt_v95()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    plan_row public.model_execution_plans%ROWTYPE;
    turn_row public.chat_turns%ROWTYPE;
    reservation public.chat_quota_reservations%ROWTYPE;
    bounded boolean;
    money_bounded boolean;
    has_reservation boolean;
    physical_now timestamptz;
BEGIN
    SELECT * INTO STRICT plan_row FROM public.model_execution_plans
     WHERE id = NEW.execution_plan_id;
    SELECT * INTO STRICT turn_row FROM public.chat_turns WHERE id = plan_row.chat_turn_id;
    SELECT * INTO reservation FROM public.chat_quota_reservations
     WHERE turn_id = turn_row.id FOR SHARE;
    has_reservation := FOUND;
    bounded := public.quota_requires_single_attempt_v95(turn_row.organization_id, turn_row.user_id);

    IF has_reservation AND reservation.single_physical_attempt IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V95 historical quota reservation cannot start new physical execution';
    END IF;
    IF bounded OR (has_reservation AND reservation.single_physical_attempt) THEN
        physical_now := clock_timestamp();
        SELECT EXISTS (SELECT 1 FROM public.organization_ai_quotas
                        WHERE organization_id = turn_row.organization_id AND enabled
                          AND monthly_cost_limit_usd IS NOT NULL)
            OR EXISTS (SELECT 1 FROM public.user_ai_quotas
                        WHERE user_id = turn_row.user_id AND enabled
                          AND monthly_cost_limit_usd IS NOT NULL)
          INTO money_bounded;
        IF NEW.attempt_number <> 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'V95 bounded chat quota permits exactly one physical attempt';
        END IF;
        IF NOT has_reservation OR reservation.state <> 'RESERVED'
           OR reservation.single_physical_attempt IS DISTINCT FROM TRUE
           OR reservation.reservation_version <> 2
           OR (money_bounded AND reservation.cost_verifiable IS DISTINCT FROM TRUE)
           OR reservation.model_route_decision_id IS DISTINCT FROM plan_row.model_route_decision_id
           OR NOT EXISTS (SELECT 1 FROM public.model_route_decisions d
                           WHERE d.id = plan_row.model_route_decision_id
                             AND d.chat_turn_id = turn_row.id
                             AND d.organization_id = reservation.organization_id
                             AND d.user_id = reservation.user_id
                             AND d.estimated_input_tokens = reservation.reserved_input_tokens
                             AND d.estimated_output_tokens = reservation.reserved_output_tokens
                             AND (NOT reservation.cost_verifiable OR
                                 (d.pricing_complete AND d.estimated_max_cost_usd IS NOT NULL
                                  AND d.estimated_max_cost_usd <= reservation.reserved_cost_usd)))
           OR reservation.physical_period_start IS NULL
           OR reservation.physical_period_end IS NULL
           OR NEW.started_at < reservation.physical_period_start
           OR NEW.started_at >= reservation.physical_period_end
           OR physical_now < reservation.physical_period_start
           OR physical_now >= reservation.physical_period_end
           OR date_trunc('month', NEW.started_at AT TIME ZONE 'UTC')
                IS DISTINCT FROM date_trunc('month', physical_now AT TIME ZONE 'UTC') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'V95 bounded chat quota requires current route reservation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_physical_quota_attempt_v95() FROM PUBLIC;
CREATE TRIGGER trg_z_physical_quota_attempt_v95
    BEFORE INSERT ON public.model_execution_attempts
    FOR EACH ROW EXECUTE FUNCTION public.guard_physical_quota_attempt_v95();

COMMENT ON COLUMN public.chat_quota_reservations.single_physical_attempt IS
    'Неизменяемый снимок наличия строгих токенных или денежных квот. NULL: история до V95.';
COMMENT ON COLUMN public.chat_quota_reservations.physical_period_start IS
    'Включительная граница месяца резервирования в настроенной временной зоне.';
COMMENT ON COLUMN public.chat_quota_reservations.physical_period_end IS
    'Исключительная граница месяца резервирования в настроенной временной зоне.';
