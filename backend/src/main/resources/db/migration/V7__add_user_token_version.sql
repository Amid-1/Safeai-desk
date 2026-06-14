/* Safeai-desk/backend/src/main/resources/db/migration/V7__add_user_token_version_and_version.sql */

alter table users
    add column token_version bigint not null default 0;

alter table users
    add column version bigint not null default 0;