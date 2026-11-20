-- Task 8: release items (变更项). English-only DDL keeps Flyway parsing independent of the
-- host charset.
--
-- A release item is the smallest publishable unit: one deploy unit (or second-party
-- library) plus one CODING build task. build_plan_id and source_branch are snapshots taken
-- when the item is created; source_branch is read-only for the item and comes from the
-- CODING task. module_path is copied from the unit and is used by commit comparison when
-- several units share a repository.
--
-- status is the main state machine state (tech design §3.2), gate_status the orthogonal
-- gate dimension (§3.3). row_version backs the optimistic lock of every transition:
-- UPDATE ... WHERE id = ? AND row_version = ?, retried up to 3 times (§4.3).
-- base_commit and last_test_pass_by/at are written by later tasks (rerun planning, gate
-- evaluation); they exist here because the item aggregate owns them.
--
-- The unique key (task_id, unit_id, source_branch) allows the same branch to appear under
-- different tasks/units but forbids a duplicate change item in one task.

CREATE TABLE release_item (
  id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id            BIGINT       NOT NULL,
  crid               VARCHAR(32)  NOT NULL COMMENT 'display business id',
  type               VARCHAR(8)   NOT NULL COMMENT 'UNIT / LIB',
  unit_id            BIGINT       NOT NULL,
  build_plan_id      VARCHAR(64)  NOT NULL COMMENT 'CODING build task snapshot',
  source_branch      VARCHAR(128) NOT NULL COMMENT 'read-only, from the CODING task',
  module_path        VARCHAR(255) NULL COMMENT 'module in a shared repository',
  version            VARCHAR(64)  NOT NULL COMMENT 'change reason / version',
  plan_release_date  DATE         NOT NULL,
  status             VARCHAR(24)  NOT NULL COMMENT 'main state machine state',
  gate_status        VARCHAR(24)  NOT NULL COMMENT 'orthogonal gate dimension',
  base_commit        CHAR(40)     NULL,
  last_test_pass_by  VARCHAR(32)  NULL,
  last_test_pass_at  DATETIME(3)  NULL,
  row_version        INT          NOT NULL DEFAULT 0,
  created_at         DATETIME(3)  NOT NULL,
  updated_at         DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_task_unit_branch (task_id, unit_id, source_branch),
  KEY idx_unit_status (unit_id, status),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;