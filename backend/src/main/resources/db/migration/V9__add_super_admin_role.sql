/* Safeai-desk/backend/src/main/resources/db/migration/V9__add_super_admin_role.sql*/
insert into roles (id, name)
values ('33333333-3333-3333-3333-333333333333', 'SUPER_ADMIN')
    on conflict (name) do nothing;
insert into user_roles (user_id, role_id)
select u.id, r.id
from users u
         cross join roles r
where u.email = 'admin@test.com'
  and r.name = 'SUPER_ADMIN'
    on conflict do nothing;