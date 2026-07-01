/* Safeai-desk/backend/src/main/resources/db/migration/V6__updated_at_triggers.sql */

create or replace function set_updated_at()
    returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;


create trigger trg_organizations_updated_at
    before update on organizations
    for each row
execute function set_updated_at();


create trigger trg_users_updated_at
    before update on users
    for each row
execute function set_updated_at();


create trigger trg_chat_sessions_updated_at
    before update on chat_sessions
    for each row
execute function set_updated_at();