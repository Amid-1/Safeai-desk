/* Safeai-desk/backend/src/main/resources/db/migration/V10__add_case_insensitive_user_email_index.sql */

drop index if exists ux_users_email_lower;

create unique index ux_users_email_lower
    on users (lower(email));