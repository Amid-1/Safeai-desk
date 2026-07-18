/* Safeai-desk/backend/src/main/resources/db/migration/V16__preserve_usage_history_on_user_delete.sql */
-- Preserve chat and usage history. Physical user deletion is forbidden.
do $$
declare
    fk_name text;
begin
    select c.conname
      into fk_name
      from pg_constraint c
      join pg_class t on t.oid = c.conrelid
      join pg_class rt on rt.oid = c.confrelid
     where c.contype = 'f'
       and t.relname = 'chat_sessions'
       and rt.relname = 'users'
     limit 1;

    if fk_name is not null then
        execute format('alter table chat_sessions drop constraint %I', fk_name);
    end if;
end
$$;

alter table chat_sessions
    add constraint fk_chat_sessions_user_restrict
        foreign key (user_id)
        references users (id)
        on delete restrict;
