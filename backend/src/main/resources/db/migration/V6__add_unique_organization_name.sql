/*Safeai-desk/backend/src/main/resources/db/migration/V6__add_unique_organization_name.sql*/
create unique index ux_organizations_name_lower
    on organizations (lower(name));