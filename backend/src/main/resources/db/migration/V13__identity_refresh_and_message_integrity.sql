/* Safeai-desk/backend/src/main/resources/db/migration/V13__identity_refresh_and_message_integrity.sql */
-- V13: canonical identities, refresh-token chain integrity and message metadata integrity.

-- ---------------------------------------------------------------------------
-- 1. Preflight checks before destructive normalization / new constraints.
-- ---------------------------------------------------------------------------

do $$
    begin
        if exists (
            select 1
            from users
            group by lower(trim(email))
            having count(*) > 1
        ) then
            raise exception
                'Cannot apply V13: users contains duplicate emails after lower(trim(email)) normalization';
        end if;
    end
$$;

do $$
    begin
        if exists (
            select 1
            from organizations
            group by lower(trim(name))
            having count(*) > 1
        ) then
            raise exception
                'Cannot apply V13: organizations contains duplicate names after lower(trim(name)) normalization';
        end if;
    end
$$;

do $$
    begin
        if exists (
            select 1
            from refresh_tokens predecessor
                     join refresh_tokens replacement
                          on replacement.id = predecessor.replaced_by_token_id
            where predecessor.user_id <> replacement.user_id
               or predecessor.token_family_id <> replacement.token_family_id
        ) then
            raise exception
                'Cannot apply V13: refresh-token replacement crosses user or token family';
        end if;
    end
$$;

do $$
    begin
        if exists (
            with recursive replacement_walk as (
                select
                    rt.id as start_id,
                    rt.id,
                    rt.replaced_by_token_id,
                    array[rt.id]::uuid[] as path,
                    false as cycle
                from refresh_tokens rt

                union all

                select
                    w.start_id,
                    next_token.id,
                    next_token.replaced_by_token_id,
                    w.path || next_token.id,
                    next_token.id = any(w.path)
                from replacement_walk w
                         join refresh_tokens next_token
                              on next_token.id = w.replaced_by_token_id
                where not w.cycle
            )
            select 1
            from replacement_walk
            where cycle
        ) then
            raise exception
                'Cannot apply V13: refresh-token replacement chain already contains a cycle';
        end if;
    end
$$;

do $$
    begin
        if exists (
            select 1
            from chat_messages
            where role = 'USER'
              and (
                model is not null
                    or input_tokens is not null
                    or output_tokens is not null
                    or cost_usd is not null
                )
        ) then
            raise exception
                'Cannot apply V13: USER chat messages contain assistant/provider metadata';
        end if;
    end
$$;

do $$
    begin
        if exists (
            select 1
            from chat_messages
            where role = 'ASSISTANT'
              and status = 'COMPLETED'
              and model is null
        ) then
            raise exception
                'Cannot apply V13: completed ASSISTANT messages without model exist';
        end if;
    end
$$;

-- ---------------------------------------------------------------------------
-- 2. Canonical email and organization-name storage.
-- ---------------------------------------------------------------------------

update users
set email = lower(trim(email))
where email <> lower(trim(email));

update organizations
set name = trim(name)
where name <> trim(name);

drop index if exists ux_users_email_lower;
create unique index ux_users_email_normalized
    on users (lower(trim(email)));

alter table users
    add constraint chk_users_email_canonical
        check (email = lower(trim(email)));

drop index if exists ux_organizations_name_lower;
create unique index ux_organizations_name_normalized
    on organizations (lower(trim(name)));

alter table organizations
    add constraint chk_organizations_name_trimmed
        check (name = trim(name));

-- ---------------------------------------------------------------------------
-- 3. A replacement must belong to the same user and token family.
-- ---------------------------------------------------------------------------

alter table refresh_tokens
    add constraint uq_refresh_tokens_id_user_family
        unique (id, user_id, token_family_id);

alter table refresh_tokens
    drop constraint fk_refresh_tokens_replaced_by;

alter table refresh_tokens
    add constraint fk_refresh_tokens_replaced_by_same_user_family
        foreign key (
                     replaced_by_token_id,
                     user_id,
                     token_family_id
            )
            references refresh_tokens (
                                       id,
                                       user_id,
                                       token_family_id
                );

-- ---------------------------------------------------------------------------
-- 4. Replacement chains are immutable once assigned and may not form cycles.
--    The advisory lock serializes chain changes inside one token family.
-- ---------------------------------------------------------------------------

create or replace function enforce_refresh_token_replacement_chain()
    returns trigger as $$
declare
    has_cycle boolean;
begin
    if tg_op = 'UPDATE'
        and old.replaced_by_token_id is not null
        and new.replaced_by_token_id is distinct from old.replaced_by_token_id then
        raise exception
            'Refresh-token replacement is immutable once assigned (token id: %)', old.id;
    end if;

    if new.replaced_by_token_id is null then
        return new;
    end if;

    perform pg_advisory_xact_lock(
            hashtextextended(new.token_family_id::text, 0)
            );

    with recursive chain as (
        select
            rt.id,
            rt.replaced_by_token_id,
            array[rt.id]::uuid[] as path,
            false as repeated
        from refresh_tokens rt
        where rt.id = new.replaced_by_token_id

        union all

        select
            rt.id,
            rt.replaced_by_token_id,
            c.path || rt.id,
            rt.id = any(c.path)
        from chain c
                 join refresh_tokens rt
                      on rt.id = c.replaced_by_token_id
        where not c.repeated
    )
    select exists (
        select 1
        from chain
        where id = new.id
           or repeated
    )
    into has_cycle;

    if has_cycle then
        raise exception
            'Refresh-token replacement would create a cycle (token id: %, replacement id: %)',
            new.id,
            new.replaced_by_token_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_refresh_tokens_replacement_chain
    before insert or update of replaced_by_token_id
    on refresh_tokens
    for each row
execute function enforce_refresh_token_replacement_chain();

-- ---------------------------------------------------------------------------
-- 5. Message metadata semantics used by usage analytics.
-- ---------------------------------------------------------------------------

alter table chat_messages
    add constraint chk_chat_messages_user_has_no_provider_metadata
        check (
            role <> 'USER'
                or (
                model is null
                    and input_tokens is null
                    and output_tokens is null
                    and cost_usd is null
                )
            );

alter table chat_messages
    add constraint chk_chat_messages_completed_assistant_has_model
        check (
            role <> 'ASSISTANT'
                or status <> 'COMPLETED'
                or model is not null
            );

-- The primary key (user_id, role_id) already supports WHERE user_id = ?.
drop index if exists idx_user_roles_user_id;