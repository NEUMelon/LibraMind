ALTER TABLE ai_conversation
    ADD COLUMN busy_token CHAR(36) NULL,
    ADD COLUMN busy_until TIMESTAMP(6) NULL;
