/* Safeai-desk/backend/src/main/resources/db/migration/V11__add_organization_version.sql */

alter table organizations
    add column if not exists version bigint not null default 0;