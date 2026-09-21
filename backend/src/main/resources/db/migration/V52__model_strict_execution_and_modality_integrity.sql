/* Safeai-desk/backend/src/main/resources/db/migration/V52__model_strict_execution_and_modality_integrity.sql */
/*
 * V52: strict v3 ALLOWED catalog binding and catalog VISION/IMAGE integrity.
 * V45–V51 remain immutable. This migration fails rather than rewriting historical evidence.
 * Review preflight failures before deployment; do not silently delete/repair evidence.
 */
DO $$
BEGIN
    IF to_regclass('public.model_route_decisions') IS NULL
       OR to_regclass('public.model_catalog_entries') IS NULL THEN
        RAISE EXCEPTION 'V52 requires the V45 model governance tables';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.model_route_decisions
        WHERE outcome = 'ALLOWED' AND decision_integrity_version >= 3
          AND (selected_catalog_entry_id IS NULL
               OR selected_catalog_version IS NULL
               OR selected_model_key IS NULL
               OR selected_provider IS NULL
               OR selected_provider_model_id IS NULL)
    ) THEN
        RAISE EXCEPTION 'V52 preflight: historical v3 ALLOWED catalog-less decisions require forensic review';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.model_catalog_entries
        WHERE vision_supported IS DISTINCT FROM image_input_supported
    ) THEN
        RAISE EXCEPTION 'V52 preflight: historical VISION/IMAGE mismatch requires catalog version review';
    END IF;
END
$$;

ALTER TABLE public.model_route_decisions
    ADD CONSTRAINT chk_model_route_decisions_strict_catalog_v52
    CHECK (
        outcome <> 'ALLOWED'
        OR decision_integrity_version < 3
        OR (selected_catalog_entry_id IS NOT NULL
            AND selected_catalog_version IS NOT NULL
            AND selected_model_key IS NOT NULL
            AND selected_provider IS NOT NULL
            AND selected_provider_model_id IS NOT NULL)
    ) NOT VALID;

ALTER TABLE public.model_route_decisions
    VALIDATE CONSTRAINT chk_model_route_decisions_strict_catalog_v52;

ALTER TABLE public.model_catalog_entries
    ADD CONSTRAINT chk_model_catalog_entries_vision_image_v52
    CHECK (vision_supported = image_input_supported) NOT VALID;

ALTER TABLE public.model_catalog_entries
    VALIDATE CONSTRAINT chk_model_catalog_entries_vision_image_v52;

COMMENT ON CONSTRAINT chk_model_route_decisions_strict_catalog_v52
ON public.model_route_decisions IS
'New integrity-v3 ALLOWED execution always has an immutable effective catalog snapshot; legacy v1/v2 evidence remains readable.';

COMMENT ON CONSTRAINT chk_model_catalog_entries_vision_image_v52
ON public.model_catalog_entries IS
'VISION capability and IMAGE input modality must agree for every catalog version.';
