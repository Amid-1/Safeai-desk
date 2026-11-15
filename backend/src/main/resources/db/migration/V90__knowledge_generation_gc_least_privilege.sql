-- SafeAI-Desk/backend/src/main/resources/db/migration/V90__knowledge_generation_gc_least_privilege.sql
-- SafeAI Desk V90: generation-GC can purge only a previously fenced
-- COLLECTING generation. The application never receives direct DELETE on chunks.
-- V1..V89 migrations stay unchanged. Separate from irreversible document erasure.

DO $prerequisites$
BEGIN
    IF to_regclass('public.knowledge_index_generations') IS NULL
       OR to_regclass('public.knowledge_document_chunks') IS NULL THEN
        RAISE EXCEPTION 'V90 requires V57 index generations and chunks';
    END IF;
    IF to_regrole('safeai_app') IS NULL THEN
        RAISE EXCEPTION 'V90 requires provisioned safeai_app';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgrelid = 'public.knowledge_document_chunks'::regclass
          AND tgname = 'trg_chunk_gc_guard_v57' AND NOT tgisinternal
          AND tgenabled <> 'D'
    ) THEN
        RAISE EXCEPTION 'V90 refuses to run without enabled V57 chunk GC fence';
    END IF;
END;
$prerequisites$;

CREATE FUNCTION public.gc_knowledge_generation_chunks_v90(
    p_document_version_id uuid,
    p_index_generation uuid
) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $gc$
DECLARE
    v_state text;
    v_deleted integer;
BEGIN
    IF p_document_version_id IS NULL OR p_index_generation IS NULL THEN
        RAISE EXCEPTION USING ERRCODE='22004',
            MESSAGE='V90 requires non-null generation identity';
    END IF;

    SELECT g.state INTO v_state
    FROM public.knowledge_index_generations g
    WHERE g.document_version_id = p_document_version_id
      AND g.index_generation = p_index_generation
    FOR UPDATE;

    IF v_state IS DISTINCT FROM 'COLLECTING' THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V90 requires a fenced COLLECTING generation';
    END IF;

    -- Existing V57 DELETE trigger is still active and rechecks the row lock.
    -- This executes in the caller's transaction; no autonomous commit is possible.
    DELETE FROM public.knowledge_document_chunks c
    WHERE c.document_version_id = p_document_version_id
      AND c.index_generation = p_index_generation;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$gc$;

REVOKE ALL ON FUNCTION public.gc_knowledge_generation_chunks_v90(uuid,uuid)
 FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.gc_knowledge_generation_chunks_v90(uuid,uuid)
 TO safeai_app;
-- The legacy direct-delete API must be closed for runtime connections.
REVOKE DELETE ON public.knowledge_document_chunks FROM safeai_app, PUBLIC;

DO $postconditions$
BEGIN
    IF has_table_privilege('safeai_app',
        'public.knowledge_document_chunks', 'DELETE') THEN
        RAISE EXCEPTION 'V90: app still has direct DELETE on Knowledge chunks';
    END IF;
    IF NOT has_function_privilege('safeai_app',
        'public.gc_knowledge_generation_chunks_v90(uuid,uuid)', 'EXECUTE') THEN
        RAISE EXCEPTION 'V90: application GC lacks constrained executor';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        CROSS JOIN LATERAL aclexplode(
            coalesce(proc.proacl, acldefault('f', proc.proowner))
        ) acl
        WHERE proc.oid = 'public.gc_knowledge_generation_chunks_v90(uuid,uuid)'::regprocedure
          AND acl.grantee = 0
          AND acl.privilege_type = 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'V90: GC executor was exposed to PUBLIC';
    END IF;
END;
$postconditions$;
