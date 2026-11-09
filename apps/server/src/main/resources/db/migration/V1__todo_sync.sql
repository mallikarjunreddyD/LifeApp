CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    username text NOT NULL UNIQUE,
    password_hash text NOT NULL
);

CREATE TABLE datasets (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL UNIQUE REFERENCES accounts(id)
);

CREATE TABLE recovery_epochs (
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true,
    last_commit bigint NOT NULL DEFAULT 0 CHECK (last_commit >= 0),
    PRIMARY KEY (dataset_id, id)
);
CREATE UNIQUE INDEX one_active_epoch ON recovery_epochs(dataset_id) WHERE active;

CREATE TABLE sessions (
    token_hash bytea PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES accounts(id),
    expires_at timestamptz NOT NULL,
    csrf_token text
);
CREATE INDEX session_expiry ON sessions(expires_at);

CREATE TABLE devices (
    dataset_id uuid NOT NULL,
    epoch_id uuid NOT NULL,
    id uuid NOT NULL,
    last_sequence bigint NOT NULL CHECK (last_sequence > 0),
    PRIMARY KEY (dataset_id, epoch_id, id),
    FOREIGN KEY (dataset_id, epoch_id) REFERENCES recovery_epochs(dataset_id, id)
);

CREATE TABLE sync_operations (
    dataset_id uuid NOT NULL,
    epoch_id uuid NOT NULL,
    id uuid NOT NULL,
    device_id uuid NOT NULL,
    device_sequence bigint NOT NULL CHECK (device_sequence > 0),
    commit_sequence bigint NOT NULL CHECK (commit_sequence > 0),
    record_id uuid NOT NULL,
    deleted boolean NOT NULL,
    canonical_payload text NOT NULL,
    PRIMARY KEY (dataset_id, epoch_id, id),
    UNIQUE (dataset_id, epoch_id, commit_sequence),
    UNIQUE (dataset_id, epoch_id, device_id, device_sequence),
    UNIQUE (dataset_id, epoch_id, record_id, id),
    FOREIGN KEY (dataset_id, epoch_id, device_id) REFERENCES devices(dataset_id, epoch_id, id)
);

CREATE TABLE todo_heads (
    dataset_id uuid NOT NULL,
    epoch_id uuid NOT NULL,
    record_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    PRIMARY KEY (dataset_id, epoch_id, record_id, operation_id),
    FOREIGN KEY (dataset_id, epoch_id, record_id, operation_id)
        REFERENCES sync_operations(dataset_id, epoch_id, record_id, id)
);

CREATE TABLE todo_projections (
    dataset_id uuid NOT NULL,
    epoch_id uuid NOT NULL,
    record_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    PRIMARY KEY (dataset_id, epoch_id, record_id),
    FOREIGN KEY (dataset_id, epoch_id, record_id, operation_id)
        REFERENCES sync_operations(dataset_id, epoch_id, record_id, id)
);

CREATE TABLE backup_jobs (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    started_at timestamptz NOT NULL,
    verified_at timestamptz,
    state text NOT NULL CHECK (state IN ('running', 'verified', 'failed')),
    CHECK ((state = 'verified') = (verified_at IS NOT NULL))
);
CREATE INDEX backup_jobs_latest ON backup_jobs(dataset_id, started_at DESC);
