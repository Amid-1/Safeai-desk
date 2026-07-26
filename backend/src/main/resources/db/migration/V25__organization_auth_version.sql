/* Safeai-desk/backend/src/main/resources/db/migration/V25__organization_auth_version.sql */
/*
 * Organization-level security epoch.
 *
 * Apply after V24. Never edit already-applied migrations.
 */

alter table public.organizations
    add column if not exists auth_version bigint not null default 0;

alter table public.organizations
    drop constraint if exists chk_organizations_auth_version_non_negative;

alter table public.organizations
    add constraint chk_organizations_auth_version_non_negative
        check (auth_version >= 0);

alter table public.refresh_tokens
    add column if not exists issued_organization_auth_version bigint;

update public.refresh_tokens token
set issued_organization_auth_version = organization.auth_version
from public.users app_user
join public.organizations organization
  on organization.id = app_user.organization_id
where app_user.id = token.user_id
  and token.issued_organization_auth_version is null;

alter table public.refresh_tokens
    alter column issued_organization_auth_version set not null;

alter table public.refresh_tokens
    drop constraint if exists chk_refresh_tokens_organization_auth_version_non_negative;

alter table public.refresh_tokens
    add constraint chk_refresh_tokens_organization_auth_version_non_negative
        check (issued_organization_auth_version >= 0);

create index if not exists idx_refresh_tokens_user_security_versions
    on public.refresh_tokens (
        user_id,
        issued_token_version,
        issued_organization_auth_version
    );

do $$
begin
    if to_regclass('public.ux_organizations_normalized_name') is null
       and not exists (
           select 1
           from pg_constraint
           where conrelid = 'public.organizations'::regclass
             and contype = 'u'
             and conname in (
                 'uq_organizations_normalized_name',
                 'uq_organizations_name_normalized'
             )
       ) then
        raise exception
            'V25 requires a unique normalized organization-name invariant';
    end if;
end
$$;
