-- SafeAI-Desk/backend/src/main/resources/db/migration/V98__usage_export_jobs.sql
-- Ограниченная устойчивая очередь; никаких изменений финансового журнала.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

CREATE TABLE public.usage_export_jobs (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    owner_org uuid NOT NULL,
    token_version bigint NOT NULL CHECK (token_version >= 0),
    org_version bigint NOT NULL CHECK (org_version >= 0),
    owner_role text NOT NULL CHECK (owner_role IN ('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')),
    kind text NOT NULL CHECK (kind IN ('SUMMARY','USERS','MODELS','DAILY','ORGANIZATIONS','PROVIDERS')),
    date_from timestamptz NOT NULL,
    date_to timestamptz NOT NULL,
    organization_id uuid,
    user_id uuid,
    model varchar(100),
    report_order text CHECK (report_order IN ('tokens','total','cost','responses','coverage')),
    budget_status varchar(40) CHECK (budget_status IN
        ('WITHIN','NEAR','EXCEEDED','NO_LIMIT','UNKNOWN','DISABLED','UNCONFIGURED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','READY','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    lease_token uuid,
    lease_until timestamptz,
    csv bytea,
    failure text CHECK (failure IN ('LIMIT_EXCEEDED','REPORT_UNAVAILABLE')),
    CHECK (isfinite(date_from) AND isfinite(date_to) AND date_from < date_to
        AND date_to - date_from <= interval '8784 hours'),
    CHECK (isfinite(created_at) AND expires_at = created_at + interval '15 minutes'),
    CHECK (owner_role = 'ROLE_SUPER_ADMIN' OR organization_id IS NOT DISTINCT FROM owner_org),
    CHECK (kind <> 'ORGANIZATIONS' OR (owner_role = 'ROLE_SUPER_ADMIN' AND organization_id IS NULL)),
    CHECK (budget_status IS NULL OR kind = 'ORGANIZATIONS'),
    CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK (lease_until IS NULL OR (isfinite(lease_until) AND lease_until <= expires_at)),
    CHECK ((state = 'READY') = (csv IS NOT NULL)),
    CHECK (csv IS NULL OR octet_length(csv) BETWEEN 1 AND 8388608),
    CHECK ((state = 'FAILED') = (failure IS NOT NULL))
);
CREATE INDEX idx_usage_export_jobs_expiry_v98 ON public.usage_export_jobs (expires_at);
CREATE INDEX idx_usage_export_jobs_owner_v98 ON public.usage_export_jobs (owner_id, expires_at);
CREATE UNIQUE INDEX idx_usage_export_jobs_active_owner_v98 ON public.usage_export_jobs (owner_id)
    WHERE state IN ('QUEUED','RUNNING');
CREATE UNIQUE INDEX idx_usage_export_jobs_one_running_v98 ON public.usage_export_jobs (state)
    WHERE state = 'RUNNING';

-- Даже runtime не может стереть активное задание или заменить готовый файл.
CREATE FUNCTION public.guard_usage_export_job_v98() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT pg_try_advisory_xact_lock(8026119801::bigint) OR NEW.state <> 'QUEUED'
            OR NEW.attempts <> 0 OR NEW.created_at > clock_timestamp()
            OR NEW.created_at < clock_timestamp() - interval '5 seconds'
            OR (SELECT count(*) FROM public.usage_export_jobs) >= 8
            OR (SELECT count(*) FROM public.usage_export_jobs WHERE owner_id = NEW.owner_id) >= 2
            OR (SELECT count(*) FROM public.usage_export_jobs WHERE state = 'QUEUED') >= 4 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 export admission bounds';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.expires_at > clock_timestamp() THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 export retention';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.state IN ('READY','FAILED') OR OLD.expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 export is terminal or expired';
    END IF;
    IF NOT ((OLD.state = 'QUEUED' AND NEW.state = 'RUNNING' AND NEW.attempts = 1)
        OR (OLD.state = 'RUNNING' AND NEW.state = 'RUNNING'
            AND OLD.lease_until <= clock_timestamp() AND NEW.attempts = OLD.attempts + 1)
        OR (OLD.state = 'RUNNING' AND NEW.state IN ('READY','FAILED') AND NEW.attempts = OLD.attempts
            AND (OLD.lease_until > clock_timestamp() OR (NEW.state = 'FAILED' AND OLD.attempts = 3)))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V98 invalid export transition';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_usage_export_job_v98() FROM PUBLIC;
CREATE TRIGGER trg_usage_export_job_v98 BEFORE INSERT OR UPDATE OR DELETE ON public.usage_export_jobs
    FOR EACH ROW EXECUTE FUNCTION public.guard_usage_export_job_v98();
REVOKE ALL ON public.usage_export_jobs FROM PUBLIC;
