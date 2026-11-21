-- Task 4: subsystem management only adds the detail columns collected on the create/edit
-- form. `subsystem` itself and its UNIQUE uk_name(name) already exist in V1__init.sql.
-- English-only DDL: Flyway parses the file with the JVM/database default charset on this
-- Windows host, so Chinese column comments are not reliable there.
ALTER TABLE subsystem
  ADD COLUMN description    VARCHAR(512) NULL AFTER agent_id,
  ADD COLUMN git_url        VARCHAR(512) NULL AFTER description,
  ADD COLUMN coding_project VARCHAR(128) NULL AFTER git_url;