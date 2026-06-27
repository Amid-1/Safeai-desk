/* Safeai-desk/backend/src/main/resources/db/migration/V2__seed_reference_data.sql */
insert into roles (id, name)
values
    ('11111111-1111-1111-1111-111111111111', 'ADMIN'),
    ('22222222-2222-2222-2222-222222222222', 'USER'),
    ('33333333-3333-3333-3333-333333333333', 'SUPER_ADMIN')
    on conflict (name) do nothing;


insert into organizations (
    id,
    name,
    enabled,
    created_at,
    version
)
values (
           '00000000-0000-0000-0000-000000000001',
           'SafeAI Platform',
           true,
           now(),
           0
       )
    on conflict (id) do nothing;