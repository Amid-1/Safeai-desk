-- SafeAI-Desk/backend/src/main/resources/db/migration/V73__organization_unicode_whitespace_canonicalization.sql
-- V73 (transactional): align DB organization-name canonicalization with Java 21.
-- V20's trigger/CHECK functions and V23's UNIQUE index remain in place.
-- Existing, applied Flyway migrations MUST NOT be modified.
-- No automatic tenant merges or deletions; ambiguous historical data aborts.
--
-- Java whitespace: Pattern "[\\p{Z}\\s]+" (Zs/Zl/Zp and ASCII HT/LF/VT/FF/CR).
-- PostgreSQL lower() and its database collation are the final unique-key authority.
-- SQL checks also enforce Java's UTF-16 String.length() <= 255 limit.
--
-- SHARE ROW EXCLUSIVE blocks concurrent organization writes for the entire
-- preflight/rewrite. ALTER TABLE will take ACCESS EXCLUSIVE briefly.
-- Schedule this on a quiet database; Flyway must execute in one transaction.

LOCK TABLE public.organizations IN SHARE ROW EXCLUSIVE MODE;

-- Explicit dependency validation. Existence of an index NAME alone does not
-- prove the required unique index is ready, valid, and on normalized_name.
DO $dependencies$
BEGIN
    IF to_regprocedure('public.canonical_organization_name(text)') IS NULL
       OR to_regprocedure('public.normalize_organization_name(text)') IS NULL THEN
        RAISE EXCEPTION
            'V73 requires V20 canonical_organization_name and normalize_organization_name functions';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_constraint c
        WHERE c.conrelid = 'public.organizations'::regclass
          AND c.conname = 'chk_organizations_name_canonical'
          AND c.contype = 'c'
    ) OR NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_constraint c
        WHERE c.conrelid = 'public.organizations'::regclass
          AND c.conname = 'chk_organizations_normalized_name_consistency'
          AND c.contype = 'c'
    ) THEN
        RAISE EXCEPTION
            'V73 requires the V20 organization canonical-name and normalized-key CHECK constraints';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_trigger t
        WHERE t.tgrelid = 'public.organizations'::regclass
          AND t.tgname = 'trg_organizations_normalize_name'
          AND NOT t.tgisinternal
          AND t.tgenabled <> 'D'
    ) THEN
        RAISE EXCEPTION
            'V73 requires an enabled V20 organization-name normalization trigger';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_index i
        JOIN pg_catalog.pg_class index_rel ON index_rel.oid = i.indexrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = index_rel.relnamespace
        JOIN pg_catalog.pg_attribute a
          ON a.attrelid = i.indrelid AND a.attname = 'normalized_name'
        WHERE n.nspname = 'public'
          AND index_rel.relname = 'ux_organizations_normalized_name'
          AND i.indrelid = 'public.organizations'::regclass
          AND i.indisunique
          AND i.indisvalid
          AND i.indisready
          AND i.indislive
          AND i.indpred IS NULL
          AND i.indexprs IS NULL
          AND i.indnkeyatts = 1
          AND i.indkey[0] = a.attnum
    ) THEN
        RAISE EXCEPTION
            'V73 requires the valid, ready, unique V23 index on organizations(normalized_name)';
    END IF;
END
$dependencies$;

-- The 24 code points consist of ASCII 9..13 + 32 (6) and Unicode Z
-- (18 others): 00A0,1680,2000..200A,2028,2029,202F,205F,3000.
-- translate() maps EACH to one ASCII space; regexp_replace collapses runs;
-- btrim removes edge spaces, including edge NBSP after replacement.
CREATE OR REPLACE FUNCTION public.canonical_organization_name_v73(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $canonical$
    SELECT btrim(
        regexp_replace(
            translate(
                value,
                chr(9) || chr(10) || chr(11) || chr(12) || chr(13) || chr(32)
                || chr(160) || chr(5760)
                || chr(8192) || chr(8193) || chr(8194) || chr(8195)
                || chr(8196) || chr(8197) || chr(8198) || chr(8199)
                || chr(8200) || chr(8201) || chr(8202)
                || chr(8232) || chr(8233) || chr(8239)
                || chr(8287) || chr(12288),
                repeat(' ', 24)
            ),
            ' +', ' ', 'g'
        )
    )
$canonical$;

-- Character.isISOControl(cp) covers U+0000..U+001F and U+007F..U+009F.
-- We examine the CANONICAL name: tabs and line breaks have become spaces.
-- PostgreSQL cannot persist U+0000 in text, but checking 0 is intentional.
CREATE OR REPLACE FUNCTION public.organization_name_has_iso_controls_v73(value text)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $control_check$
DECLARE
    i integer;
    cp integer;
BEGIN
    FOR i IN 1..char_length(value) LOOP
        cp := ascii(substr(value, i, 1));
        IF cp BETWEEN 0 AND 31 OR cp BETWEEN 127 AND 159 THEN
            RETURN true;
        END IF;
    END LOOP;
    RETURN false;
END
$control_check$;

-- Java String.length counts two UTF-16 code units for supplementary characters,
-- whereas PostgreSQL char_length counts one Unicode code point.
CREATE OR REPLACE FUNCTION public.organization_name_utf16_length_v73(value text)
RETURNS integer
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $utf16_length$
DECLARE
    i integer;
    result_length integer := 0;
BEGIN
    FOR i IN 1..char_length(value) LOOP
        IF ascii(substr(value, i, 1)) > 65535 THEN
            result_length := result_length + 2;
        ELSE
            result_length := result_length + 1;
        END IF;
    END LOOP;
    RETURN result_length;
END
$utf16_length$;

-- All checks run before touching stored names, and writers cannot race the
-- preflight because LOCK TABLE was acquired first.
DO $preflight$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.organizations o
        WHERE public.canonical_organization_name_v73(o.name) = ''
           OR public.organization_name_utf16_length_v73(
                  public.canonical_organization_name_v73(o.name)
              ) > 255
           OR char_length(lower(public.canonical_organization_name_v73(o.name))) > 255
           OR public.organization_name_has_iso_controls_v73(
                  public.canonical_organization_name_v73(o.name)
              )
    ) THEN
        RAISE EXCEPTION
            'V73 preflight: invalid canonical organization name, controls, or length; repair explicitly';
    END IF;

    -- Renamed rows must advance the Hibernate optimistic-lock version.
    -- Never wrap bigint: a saturated version must be repaired explicitly.
    IF EXISTS (
        SELECT 1
        FROM public.organizations o
        WHERE o.version = 9223372036854775807
          AND (
              o.name IS DISTINCT FROM public.canonical_organization_name_v73(o.name)
              OR o.normalized_name IS DISTINCT FROM
                 lower(public.canonical_organization_name_v73(o.name))
          )
    ) THEN
        RAISE EXCEPTION
            'V73 preflight: organization optimistic-lock version overflow; repair explicitly';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.organizations o
        GROUP BY lower(public.canonical_organization_name_v73(o.name))
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V73 preflight: organization names collide after Unicode whitespace canonicalization; resolve explicitly';
    END IF;
END
$preflight$;

-- Replace V20 function BODIES without dropping the functions, checks, triggers,
-- or existing unique index that depend on them.
CREATE OR REPLACE FUNCTION public.canonical_organization_name(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $canonical$
    SELECT public.canonical_organization_name_v73(value)
$canonical$;

CREATE OR REPLACE FUNCTION public.normalize_organization_name(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $normalized$
    SELECT lower(public.canonical_organization_name(value))
$normalized$;

-- A nonmatching row is the only row rewritten. This reduces lock duration.
-- Advance Hibernate optimistic-lock version to invalidate stale representations;
-- do NOT increment auth_version: whitespace normalization does not revoke auth.
UPDATE public.organizations o
SET name = public.canonical_organization_name(o.name),
    normalized_name = public.normalize_organization_name(o.name),
    version = o.version + 1
WHERE o.name IS DISTINCT FROM public.canonical_organization_name(o.name)
   OR o.normalized_name IS DISTINCT FROM public.normalize_organization_name(o.name);

-- V20 already enforces canonical name and normalized key consistency.
-- The extra V73 constraint enforces Java's ISO controls and UTF-16 length.
ALTER TABLE public.organizations
    ADD CONSTRAINT chk_organizations_name_no_controls_v73
    CHECK (
        name <> ''
        AND NOT public.organization_name_has_iso_controls_v73(name)
        AND public.organization_name_utf16_length_v73(name) <= 255
    );

COMMENT ON FUNCTION public.canonical_organization_name_v73(text) IS
    'V73: Unicode Z + ASCII whitespace canonicalization matching Java Pattern [\\p{Z}\\s]+.';
COMMENT ON FUNCTION public.organization_name_has_iso_controls_v73(text) IS
    'V73: Java Character.isISOControl check (C0 and C1 controls).';
COMMENT ON FUNCTION public.organization_name_utf16_length_v73(text) IS
    'V73: Java 21 String.length() in UTF-16 code units.';
COMMENT ON FUNCTION public.canonical_organization_name(text) IS
    'Organization canonicalization delegates to V73 implementation.';
COMMENT ON FUNCTION public.normalize_organization_name(text) IS
    'Organization uniqueness key is lower(canonical_organization_name(value)) in DB collation.';
