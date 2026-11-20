-- Task 7: release tasks and their members.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- A task belongs to one subsystem and is named after the release train
-- (for example 202609-V6.10.12); the name is unique inside the subsystem. creator is the
-- employee id of the user who opened it, purpose is the optional free-text goal, and
-- plan_finish_date is the planned completion date on the task card (FR-T-01). status is
-- ACTIVE / ARCHIVED; archiving itself is a later task, so new rows default to ACTIVE.
--
-- Members are unique per (task_id, user_id): re-adding the same person updates the role
-- instead of failing, which the batch add UI relies on. role stores one of the platform
-- role names DEV / TEST / OPS / PRODUCT / OWNER; PRODUCT is what freeze notifications
-- address (C22).

CREATE TABLE release_task (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  subsystem_id     BIGINT       NOT NULL,
  name             VARCHAR(128) NOT NULL,
  creator          VARCHAR(32)  NOT NULL COMMENT 'employee id of the creator',
  purpose          VARCHAR(512) NULL,
  plan_finish_date DATE         NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
  created_at       DATETIME(3)  NOT NULL,
  updated_at       DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_subsystem_task_name (subsystem_id, name),
  KEY idx_task_subsystem (subsystem_id),
  KEY idx_task_creator (creator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE release_task_member (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id    BIGINT      NOT NULL,
  user_id    VARCHAR(32) NOT NULL,
  role       VARCHAR(16) NOT NULL COMMENT 'DEV / TEST / OPS / PRODUCT / OWNER',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_task_member_user (task_id, user_id),
  KEY idx_task_member_user (user_id),
  KEY idx_task_member_role (task_id, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;