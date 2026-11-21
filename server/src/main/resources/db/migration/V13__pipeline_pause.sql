-- C1 fix: an explicit stop reason for a paused pipeline instance.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- pipeline_instance.status now also accepts PAUSED: the instance reached a node whose
-- NodeHandler is not part of this phase (VERIFY / PROD_DEPLOY). The previous node completion
-- is committed, current_node names the blocked node and status_reason explains why, so the
-- state is explainable from the row alone instead of only from a log line.
--
-- The column is nullable: a normal run and every pre-existing row have no reason.

ALTER TABLE pipeline_instance
  ADD COLUMN status_reason VARCHAR(255) NULL COMMENT 'why the instance is PAUSED'
  AFTER status;