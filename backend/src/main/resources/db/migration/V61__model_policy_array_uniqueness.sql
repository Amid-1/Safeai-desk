-- backend/src/main/resources/db/migration/V61__model_policy_array_uniqueness.sql
-- V61: model-policy array canonicality hardening.
--
-- Additive migration after V60.
-- Do NOT edit V45-V60 after they have been applied.
--
-- V59 validates every allow/deny model key individually. This migration adds
-- the missing DB invariant that one policy array cannot contain the same
-- normalized model key more than once.
--
-- Historical append-only policy rows are deliberately NOT rewritten here.
-- VALIDATE CONSTRAINT must fail if historical duplicate data exists so that
-- such data is explicitly audited/reconciled instead of being silently
-- mutated.

CREATE FUNCTION public.model_key_array_has_unique_values_v61(keys text[])
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    SELECT cardinality(keys) = (
        SELECT count(DISTINCT item.value)
        FROM unnest(keys) AS item(value)
    )
$$;

COMMENT ON FUNCTION public.model_key_array_has_unique_values_v61(text[]) IS
'Returns true when a model-key array contains no duplicate values. V59 already enforces normalized lowercase key syntax, so exact uniqueness is normalized-key uniqueness.';

ALTER TABLE public.organization_model_policies
    ADD CONSTRAINT chk_policy_model_key_arrays_unique_v61
    CHECK (
        public.model_key_array_has_unique_values_v61(allow_model_keys)
        AND public.model_key_array_has_unique_values_v61(deny_model_keys)
    )
    NOT VALID;

ALTER TABLE public.organization_model_policies
    VALIDATE CONSTRAINT chk_policy_model_key_arrays_unique_v61;
