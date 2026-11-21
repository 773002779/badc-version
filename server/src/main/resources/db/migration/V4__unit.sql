-- Task 5: deployment units, their CODING task bindings and environment DEs.
-- English-only DDL: Flyway parses the file with the JVM/database default charset on this
-- Windows host, so Chinese comments are not reliable there.

CREATE TABLE deploy_unit (
  id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  subsystem_id       BIGINT       NOT NULL,
  name               VARCHAR(128) NOT NULL,
  type               VARCHAR(16)  NOT NULL DEFAULT 'UNIT'      COMMENT 'UNIT / LIB',
  du                 VARCHAR(128) NULL,
  owner_id           VARCHAR(32)  NOT NULL,
  framework_template VARCHAR(64)  NULL,
  branch_mode        VARCHAR(16)  NOT NULL DEFAULT 'STANDARD'  COMMENT 'STANDARD / COMPATIBLE',
  status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'    COMMENT 'ACTIVE / INACTIVE',
  description        VARCHAR(512) NULL,
  module_path        VARCHAR(255) NULL COMMENT 'module in a shared repository, used by commit comparison',
  deploy_type        VARCHAR(32)  NULL,
  image_type         VARCHAR(32)  NULL,
  image_name         VARCHAR(255) NULL,
  created_at         DATETIME(3)  NOT NULL,
  updated_at         DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_subsystem_name (subsystem_id, name),
  KEY idx_unit_subsystem (subsystem_id),
  KEY idx_unit_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A unit has at most one CODING task per kind (C23). params_json is an optional
-- environment/variable template for the later CODING integration; enabled allows a
-- configured task to be switched off without deleting the row.
CREATE TABLE coding_task_binding (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_id     BIGINT      NOT NULL,
  task_kind   VARCHAR(16) NOT NULL COMMENT 'BUILD / TEST_DEPLOY / PROD_DEPLOY / DAILY_DEPLOY',
  task_id     VARCHAR(64) NOT NULL COMMENT 'CODING task identifier',
  params_json JSON        NULL,
  enabled     TINYINT     NOT NULL DEFAULT 1,
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  UNIQUE KEY uk_unit_kind (unit_id, task_kind),
  KEY idx_binding_unit (unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A DE belongs to one unit. env_kind is TEST / DAILY / PROD / DR / BLUE; deploy_batch_no
-- orders the serial multi-DE production deployment (C14) and is therefore never NULL.
CREATE TABLE env_de (
  id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_id              BIGINT       NOT NULL,
  env_kind             VARCHAR(16)  NOT NULL COMMENT 'TEST / DAILY / PROD / DR / BLUE',
  env_tag              VARCHAR(64)  NULL,
  de_name              VARCHAR(128) NOT NULL,
  resource_name        VARCHAR(128) NULL,
  coding_deploy_target VARCHAR(128) NULL,
  deploy_batch_no      INT          NOT NULL DEFAULT 0,
  created_at           DATETIME(3)  NOT NULL,
  updated_at           DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_unit_de_name (unit_id, de_name),
  KEY idx_env_de_order (unit_id, env_kind, deploy_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;