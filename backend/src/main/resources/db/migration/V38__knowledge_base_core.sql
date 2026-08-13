/* Safeai-desk/backend/src/main/resources/db/migration/V38__knowledge_base_core.sql */
-- SafeAI Desk V38
-- Knowledge Base Core:
--   * tenant-scoped knowledge bases
--   * resource memberships
--   * optimistic versions
--   * audit event types
--
-- Documents/chunks/embeddings intentionally belong to later migrations.

create table knowledge_bases (
    id uuid primary key,
    organization_id uuid not null
        references organizations(id),

    name varchar(255) not null,
    description varchar(2000),
    visibility varchar(32) not null,
    enabled boolean not null default true,

    -- Historical creator identifier. Intentionally no FK:
    -- provenance must survive permanent user deletion.
    created_by_user_id uuid not null,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,

    constraint uq_knowledge_bases_id_organization
        unique (id, organization_id),

    constraint chk_knowledge_bases_name_not_blank
        check (length(btrim(name)) > 0),

    constraint chk_knowledge_bases_name_canonical
        check (name = btrim(name)),

    constraint chk_knowledge_bases_name_no_controls
        check (name !~ '[[:cntrl:]]'),

    constraint chk_knowledge_bases_description_length
        check (description is null or length(description) <= 2000),

    constraint chk_knowledge_bases_visibility
        check (visibility in ('ORGANIZATION', 'MEMBERS')),

    constraint chk_knowledge_bases_version_non_negative
        check (version >= 0),

    constraint chk_knowledge_bases_updated_after_created
        check (updated_at >= created_at)
);

create unique index ux_knowledge_bases_org_name_lower
    on knowledge_bases (organization_id, lower(name));

create index idx_knowledge_bases_org_updated
    on knowledge_bases (
        organization_id,
        updated_at desc,
        id desc
    );

create index idx_knowledge_bases_org_visibility
    on knowledge_bases (
        organization_id,
        enabled,
        visibility,
        updated_at desc,
        id desc
    );


create or replace function validate_knowledge_base_creator()
returns trigger
language plpgsql
as $$
begin
    if not exists (
        select 1
        from users
        where id = new.created_by_user_id
          and organization_id = new.organization_id
    ) then
        raise exception
            'knowledge_base creator must belong to the same organization';
    end if;

    return new;
end;
$$;

create trigger trg_knowledge_bases_validate_creator
    before insert or update of organization_id, created_by_user_id
    on knowledge_bases
    for each row
execute function validate_knowledge_base_creator();


create table knowledge_base_memberships (
    id uuid primary key,

    knowledge_base_id uuid not null,
    organization_id uuid not null,
    user_id uuid not null,

    access_level varchar(32) not null,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,

    constraint uq_knowledge_base_memberships_kb_user
        unique (knowledge_base_id, user_id),

    constraint fk_knowledge_base_memberships_kb_org
        foreign key (knowledge_base_id, organization_id)
            references knowledge_bases (id, organization_id)
            on delete cascade,

    constraint fk_knowledge_base_memberships_user_org
        foreign key (user_id, organization_id)
            references users (id, organization_id)
            on delete cascade,

    constraint chk_knowledge_base_memberships_access
        check (access_level in ('VIEWER', 'EDITOR', 'OWNER')),

    constraint chk_knowledge_base_memberships_version_non_negative
        check (version >= 0),

    constraint chk_knowledge_base_memberships_updated_after_created
        check (updated_at >= created_at)
);

create index idx_kb_memberships_kb
    on knowledge_base_memberships (
        knowledge_base_id,
        organization_id,
        user_id
    );

create index idx_kb_memberships_user
    on knowledge_base_memberships (
        organization_id,
        user_id,
        knowledge_base_id
    );


create trigger trg_knowledge_bases_updated_at
    before update on knowledge_bases
    for each row
execute function set_updated_at();

create trigger trg_knowledge_base_memberships_updated_at
    before update on knowledge_base_memberships
    for each row
execute function set_updated_at();


insert into audit_event_types (name, description)
values
    ('KNOWLEDGE_BASE_CREATED', 'Knowledge base created'),
    ('KNOWLEDGE_BASE_UPDATED', 'Knowledge base updated'),
    ('KNOWLEDGE_BASE_MEMBER_ADDED', 'Knowledge base member added'),
    ('KNOWLEDGE_BASE_MEMBER_UPDATED', 'Knowledge base member access updated'),
    ('KNOWLEDGE_BASE_MEMBER_REMOVED', 'Knowledge base member removed')
on conflict (name) do nothing;


comment on table knowledge_bases is
    'Tenant-scoped Knowledge Base metadata. Documents are added by later migrations.';

comment on column knowledge_bases.created_by_user_id is
    'Historical creator UUID without FK so provenance survives permanent user deletion.';

comment on column knowledge_bases.visibility is
    'ORGANIZATION = enabled tenant users may read; MEMBERS = explicit membership required for ordinary USER.';

comment on table knowledge_base_memberships is
    'Resource-level Knowledge Base permissions; does not extend global SUPER_ADMIN/ADMIN/USER roles.';

comment on column knowledge_base_memberships.access_level is
    'VIEWER/EDITOR/OWNER. In V38 every membership level grants visibility; document mutation semantics are activated later.';
