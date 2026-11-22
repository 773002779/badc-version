-- P3 (T2): the approval chain becomes snapshot-scoped. English-only DDL keeps Flyway parsing
-- independent of the host charset.
--
-- Tech design 4.4 and 4.5: the chain of a release order is decided by its admission snapshot
-- (window, freeze, gate). A rejection followed by a resubmission produces a NEW snapshot, and the
-- new admission may require a different chain; the earlier steps must stay readable as history and
-- must not be "re-opened" by the new generation. Storing the snapshot id on each step is what
-- separates the generations: the application reads, appends, drops and decides per snapshot.
--
-- The table is wiped first so this migration is DETERMINISTIC on a non-empty schema: every legacy
-- row would otherwise backfill to snapshot_id = 0, and because every chain starts at step 1 the
-- new unique key (snapshot_id, step_no) would then collide. V21 was never released (development
-- and test schemas only, no production deployment), so there is no approval history to keep.

DELETE FROM approval;

ALTER TABLE approval
  ADD COLUMN snapshot_id BIGINT NOT NULL DEFAULT 0
    COMMENT 'the admission_snapshot the step belongs to' AFTER order_id,
  ADD KEY idx_approval_snapshot (snapshot_id);

-- A new snapshot's chain starts clean at step 1, so "one step number per chain" has to mean
-- "one step number per snapshot": uk_approval_step (order_id, step_no) would reject the first
-- step of every generation after the first. The order-level unique key is replaced, not
-- duplicated, so the chain of one snapshot can never contain two step 1s either.
ALTER TABLE approval
  DROP INDEX uk_approval_step,
  ADD UNIQUE KEY uk_approval_snapshot_step (snapshot_id, step_no);

-- The default only existed to backfill legacy rows, and there are none now: dropping it makes a
-- future insert that forgets snapshot_id fail loudly instead of silently landing on snapshot 0
-- (a chain nobody would ever read again).
ALTER TABLE approval
  ALTER COLUMN snapshot_id DROP DEFAULT;