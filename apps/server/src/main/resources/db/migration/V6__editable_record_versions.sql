CREATE TABLE editable_record_staging (
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    epoch_id uuid NOT NULL,
    id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('journal', 'planner', 'habits', 'notebook')),
    logical_key text NOT NULL,
    root_id uuid NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    identities jsonb NOT NULL,
    archive bytea NOT NULL CHECK (octet_length(archive) BETWEEN 1 AND 16777216),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (dataset_id, epoch_id, id)
);
CREATE INDEX editable_record_staging_expiry ON editable_record_staging(expires_at);
CREATE INDEX staged_ink_global_expiry ON staged_ink(expires_at);

CREATE TABLE editable_record_versions (
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    id uuid NOT NULL,
    sequence bigint GENERATED ALWAYS AS IDENTITY,
    kind text NOT NULL CHECK (kind IN ('journal', 'planner', 'habits', 'notebook')),
    logical_key text NOT NULL,
    root_id uuid NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    parents uuid[] NOT NULL,
    archive bytea NOT NULL CHECK (octet_length(archive) BETWEEN 1 AND 16777216),
    PRIMARY KEY (dataset_id, id),
    UNIQUE (dataset_id, sequence)
);

CREATE TABLE editable_record_heads (
    dataset_id uuid NOT NULL,
    kind text NOT NULL,
    logical_key text NOT NULL,
    version_id uuid NOT NULL,
    PRIMARY KEY (dataset_id, kind, logical_key, version_id),
    FOREIGN KEY (dataset_id, version_id) REFERENCES editable_record_versions(dataset_id, id)
);

CREATE TABLE editable_record_identities (
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    id uuid NOT NULL,
    kind text NOT NULL,
    logical_key text NOT NULL,
    role text NOT NULL,
    parent_id uuid,
    created_at text NOT NULL,
    immutable_digest text,
    PRIMARY KEY (dataset_id, id)
);
