CREATE TABLE exercises (
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    id uuid NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    category text CHECK (length(category) BETWEEN 1 AND 120),
    equipment text CHECK (length(equipment) BETWEEN 1 AND 120),
    archived boolean NOT NULL DEFAULT false,
    PRIMARY KEY (dataset_id, id)
);

CREATE TABLE workout_routines (
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    id uuid NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    PRIMARY KEY (dataset_id, id)
);

CREATE TABLE workout_routine_exercises (
    dataset_id uuid NOT NULL,
    routine_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    exercise_id uuid NOT NULL,
    PRIMARY KEY (dataset_id, routine_id, position),
    FOREIGN KEY (dataset_id, routine_id) REFERENCES workout_routines(dataset_id, id),
    FOREIGN KEY (dataset_id, exercise_id) REFERENCES exercises(dataset_id, id)
);

CREATE TABLE workout_sessions (
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    id uuid NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    local_date date NOT NULL CHECK (local_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    started_at text,
    ended_at text,
    duration_seconds integer CHECK (duration_seconds >= 0),
    calories_kcal integer CHECK (calories_kcal >= 0),
    notes text CHECK (length(notes) <= 4000),
    completed boolean NOT NULL DEFAULT false,
    CHECK (ended_at IS NULL OR (started_at IS NOT NULL AND duration_seconds IS NOT NULL)),
    CHECK (NOT completed OR ended_at IS NOT NULL),
    PRIMARY KEY (dataset_id, id)
);

CREATE TABLE workout_exercise_entries (
    dataset_id uuid NOT NULL,
    id uuid NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    session_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    round integer CHECK (round > 0),
    PRIMARY KEY (dataset_id, id),
    UNIQUE (dataset_id, session_id, position),
    FOREIGN KEY (dataset_id, session_id) REFERENCES workout_sessions(dataset_id, id),
    FOREIGN KEY (dataset_id, exercise_id) REFERENCES exercises(dataset_id, id)
);

CREATE TABLE recorded_sets (
    dataset_id uuid NOT NULL,
    id uuid NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    entry_id uuid NOT NULL,
    number integer NOT NULL CHECK (number > 0),
    weight double precision CHECK (weight >= 0 AND weight < 'Infinity'::double precision),
    weight_unit text CHECK (weight_unit IN ('KG', 'LB')),
    repetitions integer CHECK (repetitions >= 0),
    duration_seconds integer CHECK (duration_seconds > 0),
    rest_seconds integer CHECK (rest_seconds >= 0),
    completed boolean NOT NULL DEFAULT false,
    CHECK ((weight IS NULL) = (weight_unit IS NULL)),
    CHECK (NOT completed OR COALESCE(repetitions, 0) > 0 OR duration_seconds IS NOT NULL),
    PRIMARY KEY (dataset_id, id),
    UNIQUE (dataset_id, entry_id, number),
    FOREIGN KEY (dataset_id, entry_id) REFERENCES workout_exercise_entries(dataset_id, id)
);
