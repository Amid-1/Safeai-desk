-- backend/src/main/resources/db/migration/V71__usage_dirty_dates_and_recalculation_queue.sql
-- SafeAI Desk V71. Requires V28 rollups and V70 schema baseline.
-- Historical usage invalidation is committed in the same transaction as source changes.
-- Flyway versioned migration: do not edit after application.

CREATE TABLE public.usage_dirty_dates (
    usage_date date PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_usage_dirty_dates_revision_v71 CHECK (revision > 0)
);

COMMENT ON TABLE public.usage_dirty_dates IS
    'Dirty closed UTC dates requiring transactional recomputation from chat_messages/chat_turns.';
COMMENT ON COLUMN public.usage_dirty_dates.revision IS
    'Monotonically increments on source mutations; acknowledgements must check this revision.';

CREATE FUNCTION public.safeai_mark_usage_date_dirty_v71(p_date date)
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
BEGIN
    -- Current UTC day is served live. Future data is not rollup material.
    IF p_date IS NULL OR p_date >= (transaction_timestamp() AT TIME ZONE 'UTC')::date THEN
        RETURN;
    END IF;

    INSERT INTO public.usage_dirty_dates (usage_date, revision, updated_at)
    VALUES (p_date, 1, clock_timestamp())
    ON CONFLICT (usage_date) DO UPDATE
       SET revision = public.usage_dirty_dates.revision + 1,
           updated_at = clock_timestamp();
END;
$function$;

CREATE FUNCTION public.safeai_chat_messages_usage_dirty_v71()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.role = 'ASSISTANT' THEN
            PERFORM public.safeai_mark_usage_date_dirty_v71((NEW.created_at AT TIME ZONE 'UTC')::date);
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.role = 'ASSISTANT' THEN
            PERFORM public.safeai_mark_usage_date_dirty_v71((OLD.created_at AT TIME ZONE 'UTC')::date);
        END IF;
        RETURN OLD;
    END IF;

    IF ROW(OLD.role, OLD.created_at, OLD.organization_id, OLD.session_id,
           OLD.model, OLD.status, OLD.ai_response_status,
           OLD.usage_status, OLD.pricing_status, OLD.input_tokens,
           OLD.output_tokens, OLD.cost_usd)
       IS NOT DISTINCT FROM
       ROW(NEW.role, NEW.created_at, NEW.organization_id, NEW.session_id,
           NEW.model, NEW.status, NEW.ai_response_status,
           NEW.usage_status, NEW.pricing_status, NEW.input_tokens,
           NEW.output_tokens, NEW.cost_usd) THEN
        RETURN NEW;
    END IF;

    IF OLD.role = 'ASSISTANT' THEN
        PERFORM public.safeai_mark_usage_date_dirty_v71((OLD.created_at AT TIME ZONE 'UTC')::date);
    END IF;
    IF NEW.role = 'ASSISTANT' THEN
        PERFORM public.safeai_mark_usage_date_dirty_v71((NEW.created_at AT TIME ZONE 'UTC')::date);
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_safeai_usage_dirty_messages_v71
AFTER INSERT OR UPDATE OR DELETE ON public.chat_messages
FOR EACH ROW EXECUTE FUNCTION public.safeai_chat_messages_usage_dirty_v71();

CREATE FUNCTION public.safeai_chat_turns_usage_dirty_v71()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state = 'AMBIGUOUS' THEN
            PERFORM public.safeai_mark_usage_date_dirty_v71((NEW.completed_at AT TIME ZONE 'UTC')::date);
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.state = 'AMBIGUOUS' THEN
            PERFORM public.safeai_mark_usage_date_dirty_v71((OLD.completed_at AT TIME ZONE 'UTC')::date);
        END IF;
        RETURN OLD;
    END IF;

    IF ROW(OLD.state, OLD.completed_at, OLD.organization_id)
       IS NOT DISTINCT FROM
       ROW(NEW.state, NEW.completed_at, NEW.organization_id) THEN
        RETURN NEW;
    END IF;

    IF OLD.state = 'AMBIGUOUS' THEN
        PERFORM public.safeai_mark_usage_date_dirty_v71((OLD.completed_at AT TIME ZONE 'UTC')::date);
    END IF;
    IF NEW.state = 'AMBIGUOUS' THEN
        PERFORM public.safeai_mark_usage_date_dirty_v71((NEW.completed_at AT TIME ZONE 'UTC')::date);
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_safeai_usage_dirty_turns_v71
AFTER INSERT OR UPDATE OR DELETE ON public.chat_turns
FOR EACH ROW EXECUTE FUNCTION public.safeai_chat_turns_usage_dirty_v71();
