/* Safeai-desk/backend/src/main/resources/db/migration/V30__validate_usage_constraints.sql */
-- Validate historical pricing currency after V28 added the constraint as
-- NOT VALID. Migration fails deliberately if legacy non-USD data exists.

alter table chat_messages
    validate constraint chk_chat_messages_pricing_currency_usd;


alter table chat_messages
    validate constraint chk_chat_messages_reserved_usage_model;
