-- Task 17 (optimisation round 1): two free-form labels on a deployment unit.
--
-- `module` and `tag` are labels only: they never reach the build/deploy payloads and carry no
-- business rule. They exist so the unit list can be filtered by the team's own taxonomy
-- (`module` = which business module the service belongs to, `tag` = a single value such as
-- `core` / `experimental`). `module_path` (V4) is a different thing: it is the path inside a
-- shared repository and IS used by commit comparison.
--
-- English-only DDL: Flyway parses migration files with the JVM/database default charset on
-- this Windows host, so Chinese comments are not reliable here.

ALTER TABLE deploy_unit
  ADD COLUMN module VARCHAR(64) NULL COMMENT 'free-form module label, filter only',
  ADD COLUMN tag    VARCHAR(64) NULL COMMENT 'free-form tag value, filter only',
  ADD KEY idx_unit_module (module),
  ADD KEY idx_unit_tag (tag);