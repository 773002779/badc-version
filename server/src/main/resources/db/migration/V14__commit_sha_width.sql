-- I7 fix: commit_sha must hold a full commit id, not a 40-character cut.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- Git SHA-1 is 40 characters and SHA-256 is 64. V8 declared CHAR(40), and the repository
-- truncated anything longer to 40, so a SHA-256 commit was silently stored as a different
-- (shorter) value -- exactly what the rerun baseline and the production same-image check must
-- never compare against. The column is widened to 64 and a longer value is now a business
-- error instead of a truncation.

ALTER TABLE pipeline_instance
  MODIFY COLUMN commit_sha VARCHAR(64) NULL COMMENT 'built commit, reported by the engine (SHA-1 40 / SHA-256 64)';