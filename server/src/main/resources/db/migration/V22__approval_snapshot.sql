-- P3 (T2): the approval chain becomes snapshot-scoped. English-only DDL keeps Flyway parsing
-- independent of the host charset.
--
-- Tech design 4.4 and 4.5: the chain of a release order is decided by its admission snapshot
-- (window, freeze, gate). A rejection followed by a resubmission produces a NEW snapshot, and the
-- new admission may require a different chain; the earlier steps must stay readable as history and
-- must not be "re-opened" by the new generation. Storing the snapshot id on each step is what
-- separates the generations: the application reads, appends, drops and decides per snapshot.
--
-- snapshot_id = 0 is the backfill value (rows written before this migration; in practice there
-- are none, because the approval table was only reachable from the Task 1 use cases). It is kept
-- as the column default only so the ALTER can run on a non-empty table; every insert made by the
-- application names the snapshot explicitly.

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