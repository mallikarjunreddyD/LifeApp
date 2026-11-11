CREATE TABLE legacy_journal_versions (
    dataset_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    version_id uuid NOT NULL,
    PRIMARY KEY (dataset_id, entry_id),
    UNIQUE (dataset_id, version_id),
    FOREIGN KEY (dataset_id, entry_id) REFERENCES journal_ink_entries(dataset_id, id),
    FOREIGN KEY (dataset_id, version_id) REFERENCES editable_record_versions(dataset_id, id)
);
