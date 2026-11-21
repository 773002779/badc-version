-- Widen the rerun baseline to whatever a git repository may report: 40 hex (SHA-1) or 64 hex
-- (SHA-256). Publish writes the probed branch HEAD here, so a 64-char head must fit instead of
-- aborting the publish transaction with a "Data too long" error.
ALTER TABLE release_item
  MODIFY COLUMN base_commit VARCHAR(64) NULL;