CREATE TABLE editable_habit_checks (
    dataset_id uuid NOT NULL,
    id uuid NOT NULL,
    base_version_id uuid NOT NULL,
    habit_id uuid NOT NULL,
    local_date date NOT NULL,
    checked boolean NOT NULL,
    conflicted boolean NOT NULL,
    PRIMARY KEY (dataset_id, id),
    FOREIGN KEY (dataset_id, id) REFERENCES editable_record_versions(dataset_id, id),
    FOREIGN KEY (dataset_id, base_version_id) REFERENCES editable_record_versions(dataset_id, id)
);
