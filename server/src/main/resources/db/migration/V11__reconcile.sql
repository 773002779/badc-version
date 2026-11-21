-- Task 14: reconcile bookkeeping (tech design §6.3).
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- The 30s reconcile scans node_task where status = 'WAITING_EXTERNAL' and
-- updated_at < now - 90s (or the deadline passed). node_task had no updated_at column, so it is
-- added here: started_at cannot express "last observed progress" (the webhook may have reported
-- RUNNING since), and the design names updated_at explicitly.
--
-- query_failures counts consecutive failed engine queries of a task. A new column (rather than
-- reusing attempt) is the smallest change that keeps the counter atomic and queryable: attempt
-- is part of node_task.uk_idem (a manual retry inserts a new attempt row), and stuffing a
-- counter into result_summary would be neither atomic nor machine-readable. Three consecutive
-- failures escalate the task to TIMEOUT, so the counter is reset by every successful query.

ALTER TABLE node_task
  ADD COLUMN updated_at     DATETIME(3) NULL COMMENT 'last observed task progress (reconcile poll gate)',
  ADD COLUMN query_failures INT         NOT NULL DEFAULT 0 COMMENT 'consecutive failed engine queries';

-- Existing rows already carry started_at; seeding updated_at keeps a legacy WAITING_EXTERNAL row
-- from being polled only on the deadline path.
UPDATE node_task SET updated_at = started_at WHERE updated_at IS NULL AND started_at IS NOT NULL;