ALTER TABLE accounts ADD COLUMN last_totp_step bigint CHECK (last_totp_step >= 0);
CREATE TABLE owner_recovery_codes (
    owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    code_hash bytea NOT NULL,
    PRIMARY KEY (owner_id, code_hash)
);
