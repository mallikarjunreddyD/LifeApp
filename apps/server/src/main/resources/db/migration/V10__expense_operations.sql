CREATE TABLE expense_operations (
    dataset_id UUID NOT NULL REFERENCES datasets(id),
    id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    record_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('expense', 'label')),
    payload TEXT NOT NULL,
    PRIMARY KEY (dataset_id, id),
    UNIQUE (dataset_id, sequence),
    UNIQUE (dataset_id, record_id, id)
);

CREATE TABLE expense_heads (
    dataset_id UUID NOT NULL,
    record_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    PRIMARY KEY (dataset_id, record_id, operation_id),
    FOREIGN KEY (dataset_id, record_id, operation_id) REFERENCES expense_operations(dataset_id, record_id, id)
);
