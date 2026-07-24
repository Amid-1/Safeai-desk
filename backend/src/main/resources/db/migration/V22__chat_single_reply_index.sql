/* Safeai-desk/backend/src/main/resources/db/migration/V22__chat_single_reply_index.sql */
-- Must run outside a transaction.
-- V20 performs the duplicate preflight before this unique index is built.

create unique index concurrently if not exists ux_chat_messages_single_reply
    on public.chat_messages (reply_to_message_id)
    where reply_to_message_id is not null;
