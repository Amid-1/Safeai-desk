/*backend/src/main/resources/db/migration/V17__add_refresh_tokens.sql*/
create table refresh_tokens (
                                id uuid primary key,
                                user_id uuid not null references users(id),
                                token_hash varchar(128) not null unique,
                                expires_at timestamp not null,
                                revoked_at timestamp,
                                created_at timestamp not null,
                                created_by_ip varchar(100),
                                user_agent text
);

create index idx_refresh_tokens_user_id
    on refresh_tokens(user_id);

create index idx_refresh_tokens_expires_at
    on refresh_tokens(expires_at);