ALTER TABLE accounts ADD COLUMN last_totp_step bigint CHECK (last_totp_step >= 0);
CREATE TABLE owner_recovery_codes (
    owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    code_hash bytea NOT NULL,
    PRIMARY KEY (owner_id, code_hash)
);

ALTER TABLE sessions ADD COLUMN factor_verified boolean NOT NULL DEFAULT false;
CREATE FUNCTION require_owner_session_factor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT NEW.factor_verified AND EXISTS (
        SELECT 1 FROM accounts WHERE id = NEW.owner_id AND last_totp_step IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Owner session requires a verified second factor';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER owner_session_factor BEFORE INSERT ON sessions
FOR EACH ROW EXECUTE FUNCTION require_owner_session_factor();
