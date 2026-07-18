/* Safeai-desk/backend/src/main/resources/db/migration/V19__user_management_details_and_audit.sql */
alter table users
    add column last_login_at timestamptz;

create index idx_users_organization_last_login_at
    on users (organization_id, last_login_at desc)
    where last_login_at is not null;

insert into audit_event_types (name, description)
values (
    'USER_PERMANENTLY_DELETED',
    'An empty user account was permanently deleted by SUPER_ADMIN'
)
on conflict (name) do nothing;
