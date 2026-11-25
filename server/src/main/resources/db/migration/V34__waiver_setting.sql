-- P6-F (PART-06) waiver default validity: one more section of the generic app_setting key/value
-- table (V30), no new table.
--
-- WAIVER {"defaultDays":7}
--
--   defaultDays  the validity in days a waiver runs when POST /api/v1/items/{itemId}/waiver leaves
--                expireAt empty (1..90, default 7). An explicit expireAt still wins and keeps the
--                unchanged rules: later than now and at most 90 days.
--
-- The upper bound mirrors the application's unchanged 90-day waiver maximum, so the configured
-- default can never produce a request the waiver path would reject. An unreadable/invalid section
-- falls back to 7 days in the application rather than refusing every waiver.
--
-- INSERT IGNORE keeps the migration idempotent if the section was seeded by hand on a schema that
-- is already at this version (Flyway itself would normally make a repeat impossible).

INSERT IGNORE INTO app_setting (setting_key, value_json, updated_at)
VALUES ('WAIVER', '{"defaultDays":7}', NOW(3));