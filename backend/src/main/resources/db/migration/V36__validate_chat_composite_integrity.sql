/* Safeai-desk/backend/src/main/resources/db/migration/V36__validate_chat_composite_integrity.sql */
-- Low-lock validation of the self-referencing tenant-safe reply FK.
-- The constraint already protects all writes after V35.

alter table chat_messages
    validate constraint fk_chat_messages_reply_same_session_tenant;
