/* Safeai-desk/backend/src/main/resources/db/migration/V48__model_input_accounting_provenance.sql */
/* SafeAI Desk V48 — route input-accounting provenance and semantic hardening.
 *
 * V45/V46/V47 are immutable and MUST NOT be edited.
 *
 * New Java writes integrity v3. Do not run a mixed fleet containing JVMs that
 * cannot deserialize/verify v3 evidence.
 */

do $$
begin
    if to_regclass('public.model_route_decisions') is null
       or to_regclass('public.model_catalog_entries') is null then
        raise exception
            'Cannot apply V48: V45 model control plane is missing';
    end if;

    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'model_route_decisions'
          and column_name = 'decision_integrity_version'
    ) then
        raise exception
            'Cannot apply V48: V46 decision_integrity_version is missing';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 1. Exact accounting provenance for new immutable decisions.
-- ---------------------------------------------------------------------------

alter table public.model_route_decisions
    add column input_accounting_version varchar(64),
    add column additional_input_unit_upper_bound bigint;

alter table public.model_route_decisions
    drop constraint chk_model_route_decisions_integrity_version_v46;

alter table public.model_route_decisions
    add constraint chk_model_route_decisions_integrity_version_v48
        check (decision_integrity_version in (1, 2, 3));

alter table public.model_route_decisions
    add constraint chk_model_route_decisions_input_accounting_v48
        check (
            (
                decision_integrity_version < 3
                and input_accounting_version is null
                and additional_input_unit_upper_bound is null
            )
            or
            (
                decision_integrity_version = 3
                and input_accounting_version is not null
                and length(btrim(input_accounting_version))
                    between 1 and 64
                and input_accounting_version = btrim(input_accounting_version)
                and input_accounting_version !~ '[[:cntrl:]]'
                and additional_input_unit_upper_bound is not null
                and additional_input_unit_upper_bound >= 0
            )
        ) not valid;

comment on column public.model_route_decisions.input_accounting_version is
    'V3+ immutable version of deterministic input-unit accounting used for route reservation.';

comment on column public.model_route_decisions.additional_input_unit_upper_bound is
    'V3+ immutable server-side upper envelope added to base request input units before routing.';

-- NOT VALID avoids a historical validation scan. PostgreSQL still enforces
-- the check for every new row. Existing route evidence is immutable anyway.

-- ---------------------------------------------------------------------------
-- 2. New catalog snapshots cannot advertise contradictory VISION metadata.
-- Historical immutable snapshots are intentionally preserved.
-- ---------------------------------------------------------------------------

alter table public.model_catalog_entries
    add constraint chk_model_catalog_entries_vision_modality_v48
        check (
            vision_supported = image_input_supported
        ) not valid;

comment on constraint
    chk_model_catalog_entries_vision_modality_v48
    on public.model_catalog_entries is
    'New catalog versions must declare VISION capability and IMAGE input modality together.';
