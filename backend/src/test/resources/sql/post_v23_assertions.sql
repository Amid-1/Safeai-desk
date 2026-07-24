/*   # D:\Java projects\Safeai-desk\backend\src\test\resources\sql\post_v23_assertions.sql */
-- Run after Flyway V23 in a disposable/Testcontainers database.

do $$
begin
    if to_regclass('public.idx_refresh_tokens_cleanup_batch') is null then
        raise exception 'Missing idx_refresh_tokens_cleanup_batch';
    end if;

    if to_regclass('public.ux_chat_messages_single_reply') is null then
        raise exception 'Missing ux_chat_messages_single_reply';
    end if;

    if to_regclass('public.ux_organizations_normalized_name') is null then
        raise exception 'Missing ux_organizations_normalized_name';
    end if;

    if exists (
        select 1
        from public.refresh_tokens
        where issued_token_version is null
           or family_created_at is null
           or family_expires_at is null
    ) then
        raise exception 'Refresh-token hardening backfill is incomplete';
    end if;

    if exists (
        select 1
        from public.organizations
        where normalized_name <> public.normalize_organization_name(name)
    ) then
        raise exception 'Organization normalized_name drift detected';
    end if;

    if exists (
        select 1
        from public.users u
        where not exists (
            select 1 from public.user_roles ur where ur.user_id = u.id
        )
    ) then
        raise exception 'A user without roles exists';
    end if;
end
$$;
