-- P6-C (GAP-09) unit-level pipeline configuration: the safe subset that does not change the
-- four-node topology (build -> test deploy -> verify -> production deploy).
--
-- One row per deployment unit. A missing row is the documented default, so the table is only
-- written when an operator saves the configuration page; units created before this migration (and
-- units that never opened the page) keep the pre-P6-C behaviour exactly.
--
-- env_checks_enabled    run the environment-level checks (SECURITY_DYNAMIC/AUTO_TEST/DB_NORM);
--                       the effective set is the platform GATE.enabledChecks intersected with
--                       this switch. A disabled check produces no VERIFY task for a new instance
--                       and is ignored by an in-flight evaluation.
-- build_timeout_minutes BUILD/LIB_PUBLISH (and the daily BUILD) deadline from the task start.
-- deploy_timeout_minutes TEST_DEPLOY/PROD_DEPLOY/DAILY_DEPLOY deadline from the task start.
-- batch_order           FORWARD (default) / REVERSE; REVERSE only changes the batch number of the
--                       production-family tasks of a newly created online instance, so the serial
--                       deploy runs the highest original batch first. A rollback keeps its own
--                       reversed order and never reads this row.
--
-- English-only DDL keeps Flyway parsing independent of the host charset.

CREATE TABLE unit_pipeline_config (
  unit_id                BIGINT      PRIMARY KEY,
  env_checks_enabled     TINYINT     NOT NULL DEFAULT 1 COMMENT 'run the three environment-level checks',
  build_timeout_minutes  INT         NOT NULL DEFAULT 60,
  deploy_timeout_minutes INT         NOT NULL DEFAULT 8,
  batch_order            VARCHAR(8)  NOT NULL DEFAULT 'FORWARD' COMMENT 'FORWARD / REVERSE',
  updated_by             VARCHAR(32) NULL,
  updated_at             DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;