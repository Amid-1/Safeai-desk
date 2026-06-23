/*backend/src/main/resources/db/migration/V18__add_organization_enabled.sql*/
alter table organizations
    add column if not exists enabled boolean not null default true;