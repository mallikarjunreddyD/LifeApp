INSERT INTO exercises (dataset_id, id, created_at, updated_at, revision, name)
SELECT id, '70000000-0000-4000-8000-000000000001', '2026-11-05T08:00:00Z', '2026-11-05T08:00:00Z', 1, 'Drill Press' FROM datasets;
INSERT INTO workout_sessions (dataset_id, id, created_at, updated_at, revision, local_date)
SELECT id, '70000000-0000-4000-8000-000000000002', '2026-11-05T08:00:00Z', '2026-11-05T08:00:00Z', 1, '2026-11-05' FROM datasets;
INSERT INTO workout_exercise_entries (dataset_id, id, created_at, updated_at, revision, session_id, exercise_id, position)
SELECT id, '70000000-0000-4000-8000-000000000003', '2026-11-05T08:00:00Z', '2026-11-05T08:00:00Z', 1,
    '70000000-0000-4000-8000-000000000002', '70000000-0000-4000-8000-000000000001', 0 FROM datasets;
INSERT INTO recorded_sets (dataset_id, id, created_at, updated_at, revision, entry_id, number, weight, weight_unit, repetitions, completed)
SELECT id, '70000000-0000-4000-8000-000000000004', '2026-11-05T08:00:00Z', '2026-11-05T08:00:00Z', 1,
    '70000000-0000-4000-8000-000000000003', 1, 22.5, 'LB', 8, true FROM datasets;
