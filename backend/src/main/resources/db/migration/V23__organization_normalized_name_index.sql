/* Safeai-desk/backend/src/main/resources/db/migration/V23__organization_normalized_name_index.sql */
-- Must run outside a transaction.
-- The existing lower(trim(name)) index is intentionally retained temporarily
-- for compatibility with old repository queries. Remove it only after all
-- reads use normalized_name.

create unique index concurrently if not exists ux_organizations_normalized_name
    on public.organizations (normalized_name);
