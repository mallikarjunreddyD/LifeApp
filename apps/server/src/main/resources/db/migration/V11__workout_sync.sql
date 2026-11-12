CREATE TABLE workout_sync_versions (
    dataset_id UUID NOT NULL REFERENCES datasets(id),
    version UUID NOT NULL,
    record_id UUID NOT NULL,
    parent UUID,
    kind TEXT NOT NULL CHECK (kind IN ('exercise', 'routine', 'session')),
    payload TEXT NOT NULL,
    PRIMARY KEY (dataset_id, version),
    UNIQUE (dataset_id, record_id, version),
    FOREIGN KEY (dataset_id, record_id, parent) REFERENCES workout_sync_versions(dataset_id, record_id, version)
);
CREATE TABLE workout_sync_heads (
    dataset_id UUID NOT NULL,
    record_id UUID NOT NULL,
    version UUID NOT NULL,
    PRIMARY KEY (dataset_id, record_id),
    FOREIGN KEY (dataset_id, record_id, version) REFERENCES workout_sync_versions(dataset_id, record_id, version)
);
