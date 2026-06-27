/* Safeai-desk/backend/src/main/resources/db/migration/V4__refresh_token_rotation.sql*/
ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS token_family_id uuid;

ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS replaced_by_token_id uuid;

ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS last_used_at timestamptz;


UPDATE refresh_tokens
SET token_family_id = id
WHERE token_family_id IS NULL;


ALTER TABLE refresh_tokens
    ALTER COLUMN token_family_id SET NOT NULL;


ALTER TABLE refresh_tokens
    ALTER COLUMN user_agent TYPE varchar(512)
        USING left(user_agent, 512);


DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'fk_refresh_tokens_replaced_by'
        ) THEN
            ALTER TABLE refresh_tokens
                ADD CONSTRAINT fk_refresh_tokens_replaced_by
                    FOREIGN KEY (replaced_by_token_id)
                        REFERENCES refresh_tokens(id);
        END IF;
    END $$;


CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_family_id
    ON refresh_tokens(token_family_id);


CREATE INDEX IF NOT EXISTS idx_refresh_tokens_revoked_at
    ON refresh_tokens(revoked_at);


CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_family_active
    ON refresh_tokens(token_family_id)
    WHERE revoked_at IS NULL;