-- CODING connection: remember the last connectivity probe so the settings page can show
-- "when did we last verify this connection, and how did it go" without calling the engine
-- on every page load.
--
-- English-only DDL for the same reason as V5: Flyway parses the file with the platform
-- default charset on this Windows host, Chinese comments are not reliable there.
--
-- last_probe_ok is NULL until the first probe. A draft probe (test before save) does not
-- write these columns; only testing a stored connection does.

ALTER TABLE coding_conn
  ADD COLUMN last_probe_at      DATETIME(3)  NULL,
  ADD COLUMN last_probe_ok      TINYINT(1)   NULL,
  ADD COLUMN last_probe_message VARCHAR(500) NULL;