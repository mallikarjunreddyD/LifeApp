-- Apply to an isolated restored database before starting the API.
DELETE FROM sessions;
DO $$
BEGIN
    IF to_regclass('public.editable_record_staging') IS NOT NULL THEN
        DELETE FROM editable_record_staging;
    END IF;
    IF to_regclass('public.staged_ink') IS NOT NULL THEN
        DELETE FROM staged_ink;
    END IF;
END;
$$;
UPDATE backup_jobs SET state = 'failed' WHERE state = 'running';
CREATE TEMP TABLE epoch_map ON COMMIT DROP AS
SELECT dataset_id, id AS old_epoch, gen_random_uuid() AS new_epoch, last_commit
FROM recovery_epochs WHERE active;
UPDATE recovery_epochs SET active = false WHERE active;
INSERT INTO recovery_epochs (dataset_id, id, active, last_commit)
SELECT dataset_id, new_epoch, true, last_commit FROM epoch_map;
INSERT INTO devices
SELECT d.dataset_id, m.new_epoch, d.id, d.last_sequence
FROM devices d JOIN epoch_map m ON (d.dataset_id, d.epoch_id) = (m.dataset_id, m.old_epoch);
INSERT INTO sync_operations
SELECT o.dataset_id, m.new_epoch, o.id, o.device_id, o.device_sequence,
       o.commit_sequence, o.record_id, o.deleted, o.canonical_payload
FROM sync_operations o JOIN epoch_map m ON (o.dataset_id, o.epoch_id) = (m.dataset_id, m.old_epoch);
INSERT INTO todo_heads
SELECT h.dataset_id, m.new_epoch, h.record_id, h.operation_id
FROM todo_heads h JOIN epoch_map m ON (h.dataset_id, h.epoch_id) = (m.dataset_id, m.old_epoch);
INSERT INTO todo_projections
SELECT p.dataset_id, m.new_epoch, p.record_id, p.operation_id
FROM todo_projections p JOIN epoch_map m ON (p.dataset_id, p.epoch_id) = (m.dataset_id, m.old_epoch);
