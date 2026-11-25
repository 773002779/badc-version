-- P6-A (GAP-07) second-party library: the artifact coordinates of a LIB unit.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- A LIB unit is shaped like a deployment unit but produces an artifact instead of an image or a
-- deployment, so it carries the Maven groupId/artifactId of that artifact. The version is NOT
-- stored here: it stays the 变更项 version (the build produces it), and a version rule engine
-- needs the artifact repository metadata this slice deliberately does not integrate.
--
-- Only a LIB row fills these columns; a UNIT ignores the two fields (the application service
-- writes NULL), so no existing unit changes meaning. module_path already exists and is the
-- documented heuristic for the LIB dependents endpoint (module_path == artifactId).

ALTER TABLE deploy_unit
  ADD COLUMN artifact_group_id    VARCHAR(128) NULL COMMENT 'LIB: Maven groupId',
  ADD COLUMN artifact_artifact_id VARCHAR(128) NULL COMMENT 'LIB: Maven artifactId';

-- The dependents query of one library: the UNIT units of the same subsystem whose module_path
-- equals the library's artifactId.
ALTER TABLE deploy_unit
  ADD KEY idx_unit_subsystem_module (subsystem_id, module_path);