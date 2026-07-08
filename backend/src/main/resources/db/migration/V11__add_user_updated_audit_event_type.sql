/* Safeai-desk/backend/src/main/resources/db/migration/V11__add_user_updated_audit_event_type.sql */
insert into audit_event_types (name, description)
values ('USER_UPDATED', 'User profile data was updated')
on conflict (name) do nothing;