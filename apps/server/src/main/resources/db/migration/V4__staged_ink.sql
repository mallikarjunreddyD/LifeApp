CREATE TABLE staged_ink (
    dataset_id uuid NOT NULL,
    epoch_id uuid NOT NULL,
    id uuid NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    bytes bytea NOT NULL CHECK (octet_length(bytes) BETWEEN 32 AND 20000012),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (dataset_id, epoch_id, id),
    FOREIGN KEY (dataset_id, epoch_id) REFERENCES recovery_epochs(dataset_id, id)
);

CREATE INDEX staged_ink_expiry ON staged_ink(dataset_id, expires_at);
