CREATE TABLE journal_ink_entries (
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    id uuid NOT NULL,
    local_date date NOT NULL,
    page_id uuid NOT NULL,
    payload jsonb NOT NULL,
    PRIMARY KEY (dataset_id, id),
    UNIQUE (dataset_id, local_date),
    UNIQUE (dataset_id, page_id),
    CHECK (id <> page_id)
);

CREATE TABLE journal_ink_strokes (
    dataset_id uuid NOT NULL,
    id uuid NOT NULL,
    page_id uuid NOT NULL,
    stroke_order bigint NOT NULL CHECK (stroke_order >= 0),
    upload_id uuid NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    payload jsonb NOT NULL,
    bytes bytea NOT NULL CHECK (octet_length(bytes) BETWEEN 32 AND 20000012),
    PRIMARY KEY (dataset_id, id),
    UNIQUE (dataset_id, page_id, stroke_order),
    UNIQUE (dataset_id, upload_id),
    FOREIGN KEY (dataset_id, page_id) REFERENCES journal_ink_entries(dataset_id, page_id),
    CHECK (id <> page_id)
);
