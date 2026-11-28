-- SafeAI-Desk/backend/src/main/resources/db/migration/V96__chat_quota_admission_fence.sql
-- Конкурентное изменение финансовых политик и точный расчётный период.
-- V95 и её защитные триггеры остаются без изменений.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

-- Общая блокировка только для транзакций допуска; эксклюзивная для
-- изменения политики, до захвата строк. Отсутствующая строка тоже защищена.
CREATE FUNCTION public.lock_quota_admission_v96()
RETURNS void LANGUAGE plpgsql
SET search_path = pg_catalog, public SET lock_timeout = '5s' AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V96 quota admission requires READ COMMITTED';
    END IF;
    PERFORM pg_advisory_xact_lock_shared(754962, 1);
END;
$$;
REVOKE ALL ON FUNCTION public.lock_quota_admission_v96() FROM PUBLIC;

CREATE FUNCTION public.lock_quota_policy_write_v96()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public SET lock_timeout = '5s' AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(754962, 1);
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.lock_quota_policy_write_v96() FROM PUBLIC;

CREATE TRIGGER trg_quota_org_write_v96
    BEFORE INSERT OR UPDATE OR DELETE ON public.organization_ai_quotas
    FOR EACH STATEMENT EXECUTE FUNCTION public.lock_quota_policy_write_v96();
CREATE TRIGGER trg_quota_user_write_v96
    BEFORE INSERT OR UPDATE OR DELETE ON public.user_ai_quotas
    FOR EACH STATEMENT EXECUTE FUNCTION public.lock_quota_policy_write_v96();

CREATE FUNCTION public.lock_quota_admission_statement_v96()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    PERFORM public.lock_quota_admission_v96();
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.lock_quota_admission_statement_v96() FROM PUBLIC;
CREATE TRIGGER trg_quota_admission_insert_v96
    BEFORE INSERT ON public.chat_quota_reservations
    FOR EACH STATEMENT EXECUTE FUNCTION public.lock_quota_admission_statement_v96();
CREATE TRIGGER trg_quota_attempt_insert_v96
    BEFORE INSERT ON public.model_execution_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION public.lock_quota_admission_statement_v96();

-- UUID меняется при редактировании содержимого либо пересоздании строки.
-- updated_at и служебные обновления версии не аннулируют финансовый допуск.
ALTER TABLE public.organization_ai_quotas
    ADD COLUMN quota_revision_v96 uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE public.user_ai_quotas
    ADD COLUMN quota_revision_v96 uuid NOT NULL DEFAULT gen_random_uuid();

CREATE FUNCTION public.refresh_quota_revision_v96()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.quota_revision_v96 := gen_random_uuid();
    ELSIF ROW(NEW.enabled, NEW.monthly_request_limit,
              NEW.monthly_input_token_limit, NEW.monthly_output_token_limit,
              NEW.monthly_cost_limit_usd)
        IS DISTINCT FROM ROW(OLD.enabled, OLD.monthly_request_limit,
              OLD.monthly_input_token_limit, OLD.monthly_output_token_limit,
              OLD.monthly_cost_limit_usd) THEN
        NEW.quota_revision_v96 := gen_random_uuid();
    ELSE
        NEW.quota_revision_v96 := OLD.quota_revision_v96;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.refresh_quota_revision_v96() FROM PUBLIC;
CREATE TRIGGER trg_quota_org_revision_v96
    BEFORE INSERT OR UPDATE ON public.organization_ai_quotas
    FOR EACH ROW EXECUTE FUNCTION public.refresh_quota_revision_v96();
CREATE TRIGGER trg_quota_user_revision_v96
    BEFORE INSERT OR UPDATE ON public.user_ai_quotas
    FOR EACH ROW EXECUTE FUNCTION public.refresh_quota_revision_v96();

-- Одно доверенное значение для всех backend-инстансов. Runtime только читает.
CREATE TABLE public.chat_quota_calendar_v96 (
    singleton boolean PRIMARY KEY CHECK (singleton),
    period_zone text NOT NULL CHECK (length(period_zone) BETWEEN 1 AND 100)
);
INSERT INTO public.chat_quota_calendar_v96 VALUES (true, 'UTC');
REVOKE ALL ON TABLE public.chat_quota_calendar_v96 FROM PUBLIC;

ALTER TABLE public.chat_quota_reservations
    ADD COLUMN organization_quota_revision_v96 uuid,
    ADD COLUMN user_quota_revision_v96 uuid,
    ADD COLUMN period_zone_v96 text;

CREATE FUNCTION public.guard_quota_calendar_v96()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V96 calendar must exist';
    END IF;
    IF NEW.period_zone IS DISTINCT FROM OLD.period_zone AND (
        NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.period_zone)
        OR EXISTS (SELECT 1 FROM public.chat_quota_reservations WHERE period_zone_v96 IS NOT NULL)
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V96 calendar change requires migration';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_quota_calendar_v96() FROM PUBLIC;
CREATE TRIGGER trg_quota_calendar_write_v96
    BEFORE INSERT OR UPDATE OR DELETE ON public.chat_quota_calendar_v96
    FOR EACH STATEMENT EXECUTE FUNCTION public.lock_quota_policy_write_v96();
CREATE TRIGGER trg_quota_calendar_guard_v96
    BEFORE INSERT OR UPDATE OR DELETE ON public.chat_quota_calendar_v96
    FOR EACH ROW EXECUTE FUNCTION public.guard_quota_calendar_v96();

CREATE FUNCTION public.guard_quota_snapshot_v96()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    zone_name text;
    expected_start timestamptz;
    expected_end timestamptz;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.organization_quota_revision_v96, NEW.user_quota_revision_v96, NEW.period_zone_v96)
            IS DISTINCT FROM ROW(OLD.organization_quota_revision_v96,
                                 OLD.user_quota_revision_v96, OLD.period_zone_v96) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = 'V96 quota snapshot is immutable';
        END IF;
        RETURN NEW;
    END IF;

    -- Старый формат сохраняется для истории, но не служит финансовым допуском.
    NEW.organization_quota_revision_v96 := NULL;
    NEW.user_quota_revision_v96 := NULL;
    NEW.period_zone_v96 := NULL;
    IF NEW.reservation_version <> 2 THEN
        RETURN NEW;
    END IF;

    SELECT period_zone INTO STRICT zone_name FROM public.chat_quota_calendar_v96
        WHERE singleton;
    expected_start := NEW.period_start::timestamp AT TIME ZONE zone_name;
    expected_end := (NEW.period_start + interval '1 month') AT TIME ZONE zone_name;
    IF NEW.period_start IS DISTINCT FROM date_trunc('month', NEW.created_at AT TIME ZONE zone_name)::date
        OR NEW.physical_period_start IS DISTINCT FROM expected_start
        OR NEW.physical_period_end IS DISTINCT FROM expected_end THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V96 reservation period disagrees with database calendar';
    END IF;
    SELECT quota_revision_v96 INTO NEW.organization_quota_revision_v96
        FROM public.organization_ai_quotas WHERE organization_id = NEW.organization_id;
    SELECT quota_revision_v96 INTO NEW.user_quota_revision_v96
        FROM public.user_ai_quotas WHERE user_id = NEW.user_id;
    NEW.period_zone_v96 := zone_name;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_quota_snapshot_v96() FROM PUBLIC;
CREATE TRIGGER trg_quota_admission_snapshot_v96
    BEFORE INSERT OR UPDATE ON public.chat_quota_reservations
    FOR EACH ROW EXECUTE FUNCTION public.guard_quota_snapshot_v96();

-- Повторная проверка применяется как при STARTED, так и перед отправкой.
-- Любая смена действующей политики требует нового допуска, не переписывания резерва.
CREATE FUNCTION public.assert_quota_admission_v96(plan_id uuid, started timestamptz)
RETURNS boolean LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    plan_row public.model_execution_plans%ROWTYPE;
    turn_row public.chat_turns%ROWTYPE;
    org_policy public.organization_ai_quotas%ROWTYPE;
    user_policy public.user_ai_quotas%ROWTYPE;
    reservation public.chat_quota_reservations%ROWTYPE;
    requires_reservation boolean;
    has_reservation boolean;
    zone_name text;
    physical_now timestamptz;
BEGIN
    PERFORM public.lock_quota_admission_v96();
    SELECT * INTO STRICT plan_row FROM public.model_execution_plans WHERE id = plan_id;
    SELECT * INTO STRICT turn_row FROM public.chat_turns WHERE id = plan_row.chat_turn_id;
    SELECT * INTO reservation FROM public.chat_quota_reservations
        WHERE turn_id = plan_row.chat_turn_id FOR SHARE;
    has_reservation := FOUND;
    SELECT * INTO org_policy FROM public.organization_ai_quotas
        WHERE organization_id = plan_row.organization_id FOR SHARE;
    SELECT * INTO user_policy FROM public.user_ai_quotas
        WHERE user_id = turn_row.user_id FOR SHARE;
    requires_reservation :=
        coalesce(org_policy.enabled AND (org_policy.monthly_request_limit IS NOT NULL
            OR org_policy.monthly_input_token_limit IS NOT NULL
            OR org_policy.monthly_output_token_limit IS NOT NULL
            OR org_policy.monthly_cost_limit_usd IS NOT NULL), false)
        OR coalesce(user_policy.enabled AND (user_policy.monthly_request_limit IS NOT NULL
            OR user_policy.monthly_input_token_limit IS NOT NULL
            OR user_policy.monthly_output_token_limit IS NOT NULL
            OR user_policy.monthly_cost_limit_usd IS NOT NULL), false);

    IF NOT requires_reservation AND (NOT has_reservation OR reservation.reservation_version = 1) THEN
        RETURN true;
    END IF;
    SELECT period_zone INTO STRICT zone_name FROM public.chat_quota_calendar_v96 WHERE singleton;
    physical_now := clock_timestamp();
    IF NOT has_reservation OR reservation.state <> 'RESERVED'
        OR reservation.reservation_version <> 2
        OR reservation.model_route_decision_id IS DISTINCT FROM plan_row.model_route_decision_id
        OR reservation.organization_id IS DISTINCT FROM plan_row.organization_id
        OR reservation.user_id IS DISTINCT FROM turn_row.user_id
        OR reservation.period_zone_v96 IS DISTINCT FROM zone_name
        OR reservation.organization_quota_revision_v96 IS DISTINCT FROM org_policy.quota_revision_v96
        OR reservation.user_quota_revision_v96 IS DISTINCT FROM user_policy.quota_revision_v96
        OR reservation.physical_period_start IS NULL OR reservation.physical_period_end IS NULL
        OR started IS NULL OR NOT isfinite(started) OR started < reservation.physical_period_start
        OR started >= reservation.physical_period_end
        OR physical_now < reservation.physical_period_start OR physical_now >= reservation.physical_period_end THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V96 current quota admission required';
    END IF;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.assert_quota_admission_v96(uuid, timestamptz) FROM PUBLIC;

CREATE FUNCTION public.guard_physical_quota_v96()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    PERFORM public.assert_quota_admission_v96(NEW.execution_plan_id, NEW.started_at);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_physical_quota_v96() FROM PUBLIC;
CREATE TRIGGER trg_zz_physical_quota_v96
    BEFORE INSERT ON public.model_execution_attempts
    FOR EACH ROW EXECUTE FUNCTION public.guard_physical_quota_v96();

-- Только существующий STARTED, привязанный к этому плану и попытке.
CREATE FUNCTION public.check_quota_dispatch_v96(plan_id uuid, provider_attempt uuid)
RETURNS boolean LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    started timestamptz;
BEGIN
    PERFORM public.lock_quota_admission_v96();
    SELECT started_at INTO STRICT started FROM public.model_execution_attempts
        WHERE execution_plan_id = plan_id AND provider_attempt_id = provider_attempt
            AND outcome = 'STARTED' FOR SHARE;
    RETURN public.assert_quota_admission_v96(plan_id, started);
END;
$$;
REVOKE ALL ON FUNCTION public.check_quota_dispatch_v96(uuid, uuid) FROM PUBLIC;
