-- SafeAI-Desk/backend/src/main/resources/db/migration/V99__usage_export_job_guard.sql
-- Усиление V98 без изменения её контрольной суммы, данных и прав runtime.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE public.usage_export_jobs IN SHARE ROW EXCLUSIVE MODE;

-- Не исправляем сомнительную историю автоматически.
DO $$
BEGIN
    IF (SELECT count(*) FROM public.usage_export_jobs) > 8
        OR (SELECT count(*) FROM public.usage_export_jobs WHERE state = 'QUEUED') > 4
        OR EXISTS (SELECT 1 FROM public.usage_export_jobs GROUP BY owner_id HAVING count(*) > 2) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V99 invalid export queue inventory';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_usage_export_job_v98()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    checked_at timestamptz;
BEGIN
    -- Advisory lock does not refresh a REPEATABLE READ snapshot.
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING ERRCODE = '25000', MESSAGE = 'V99 export requires READ COMMITTED';
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- Separate lock and counting statements; VOLATILE obtains a fresh snapshot for the latter.
        IF NOT pg_try_advisory_xact_lock(8026119801::bigint) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 export admission bounds';
        END IF;
        checked_at := clock_timestamp();
        IF NEW.state <> 'QUEUED' OR NEW.attempts <> 0 OR NEW.created_at > checked_at
            OR NEW.created_at < checked_at - interval '5 seconds'
            OR (SELECT count(*) FROM public.usage_export_jobs) >= 8
            OR (SELECT count(*) FROM public.usage_export_jobs WHERE owner_id = NEW.owner_id) >= 2
            OR (SELECT count(*) FROM public.usage_export_jobs WHERE state = 'QUEUED') >= 4 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 export admission bounds';
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.expires_at > clock_timestamp() THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 export retention';
        END IF;
        RETURN OLD;
    ELSE
        checked_at := clock_timestamp();
        IF ROW(NEW.id, NEW.owner_id, NEW.owner_org, NEW.token_version, NEW.org_version, NEW.owner_role,
               NEW.kind, NEW.date_from, NEW.date_to, NEW.organization_id, NEW.user_id, NEW.model,
               NEW.report_order, NEW.budget_status, NEW.created_at, NEW.expires_at)
           IS DISTINCT FROM
           ROW(OLD.id, OLD.owner_id, OLD.owner_org, OLD.token_version, OLD.org_version, OLD.owner_role,
               OLD.kind, OLD.date_from, OLD.date_to, OLD.organization_id, OLD.user_id, OLD.model,
               OLD.report_order, OLD.budget_status, OLD.created_at, OLD.expires_at) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V99 export identity is immutable';
        END IF;
        IF OLD.state IN ('READY','FAILED') OR OLD.expires_at <= checked_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 export is terminal or expired';
        END IF;
        IF NOT ((OLD.state = 'QUEUED' AND NEW.state = 'RUNNING' AND NEW.attempts = 1)
            OR (OLD.state = 'RUNNING' AND NEW.state = 'RUNNING'
                AND OLD.lease_until <= checked_at AND NEW.attempts = OLD.attempts + 1)
            OR (OLD.state = 'RUNNING' AND NEW.state IN ('READY','FAILED') AND NEW.attempts = OLD.attempts
                AND (OLD.lease_until > checked_at OR (NEW.state = 'FAILED' AND OLD.attempts = 3)))) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 invalid export transition';
        END IF;
        IF NEW.state = 'RUNNING' AND (NEW.lease_token IS NULL OR NEW.lease_until IS NULL
            OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token OR NEW.lease_until <= checked_at
            OR NEW.lease_until > least(NEW.expires_at, checked_at + interval '2 minutes')) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V99 invalid export lease';
        END IF;
    END IF;

    -- No new job or READY for an owner whose current authorization no longer matches.
    IF (TG_OP = 'INSERT' OR NEW.state = 'READY') AND NOT EXISTS (
        SELECT 1 FROM public.users u JOIN public.organizations o ON o.id = u.organization_id
        JOIN public.user_roles ur ON ur.user_id = u.id JOIN public.roles r ON r.id = ur.role_id
        WHERE u.id = NEW.owner_id AND u.organization_id = NEW.owner_org AND u.enabled AND o.enabled
          AND u.token_version = NEW.token_version AND o.auth_version = NEW.org_version
          AND 'ROLE_' || r.name = NEW.owner_role
          AND (SELECT count(*) FROM public.user_roles WHERE user_id = u.id) = 1
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V99 export authorization changed';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_usage_export_job_v98() FROM PUBLIC;

COMMENT ON FUNCTION public.guard_usage_export_job_v98() IS
    'V99: serial admission in READ COMMITTED, immutable identity, bounded lease and current owner authorization.';
