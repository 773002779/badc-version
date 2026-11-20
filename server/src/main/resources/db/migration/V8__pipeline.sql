-- Task 9: release order, pipeline instance pre-generation and the deployment-unit lock.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- release_order is created once per published 变更项 and is reused by reruns (the publish
-- number does not change, tech design §5.3). pipeline_instance is created once per execution
-- (initial run, rerun-from-node, full rerun, rollback); an instance pre-generates its nodes
-- and node tasks at creation time (§5.1, aggregate boundary §3.1). deploy_unit_lock is the DB
-- backstop for the Redis unit lock: the primary key on unit_id means a second insert fails,
-- and an expired row may be reclaimed (§4.3, §9).
--
-- node_task idempotency: the design's uk_idem uses IFNULL(batch_no,0)/IFNULL(target_id,0)
-- because its columns are nullable. The platform ruling replaces that with
-- NOT NULL DEFAULT 0 so the unique key is a plain six-column key. CHECK tasks of the VERIFY
-- node have no DE target, so target_id carries the stable check-type code (1..7) instead.

CREATE TABLE release_order (
  id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
  publish_no           VARCHAR(32) NOT NULL COMMENT 'business publish number, stable across reruns',
  item_id              BIGINT      NOT NULL,
  oa_ticket_no         VARCHAR(64) NULL,
  planned_release_time DATETIME(3) NULL,
  window_result        VARCHAR(8)  NULL COMMENT 'IN / OUT',
  deadline_at          DATETIME(3) NULL COMMENT 'overdue threshold',
  status               VARCHAR(24) NOT NULL COMMENT 'order lifecycle marker',
  row_version          INT         NOT NULL DEFAULT 0,
  created_at           DATETIME(3) NOT NULL,
  UNIQUE KEY uk_publish_no (publish_no),
  KEY idx_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pipeline_instance (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id     BIGINT      NOT NULL,
  pipeline_no  VARCHAR(32) NOT NULL,
  exec_type    VARCHAR(8)  NOT NULL COMMENT 'ONLINE / DAILY',
  trigger_type VARCHAR(16) NOT NULL COMMENT 'INITIAL / RERUN_FROM_NODE / RERUN_FULL / ROLLBACK',
  current_node VARCHAR(24) NULL,
  status       VARCHAR(16) NOT NULL COMMENT 'PENDING / RUNNING / DONE / FAILED / CLOSED / TERMINATED',
  image_digest VARCHAR(80) NULL,
  commit_sha   CHAR(40)    NULL,
  start_at     DATETIME(3) NULL,
  end_at       DATETIME(3) NULL,
  created_at   DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pipeline_no (pipeline_no),
  KEY idx_order (order_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pipeline_node (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  instance_id BIGINT      NOT NULL,
  node_type   VARCHAR(24) NOT NULL COMMENT 'BUILD / TEST_DEPLOY / VERIFY / PROD_DEPLOY',
  seq_no      INT         NOT NULL COMMENT '0-based execution order',
  status      VARCHAR(16) NOT NULL COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / SKIPPED',
  started_at  DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  UNIQUE KEY uk_instance_node (instance_id, node_type),
  KEY idx_node_order (instance_id, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE node_task (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  instance_id    BIGINT       NOT NULL,
  node_type      VARCHAR(24)  NOT NULL,
  task_type      VARCHAR(24)  NOT NULL COMMENT 'CODE_CHECK/PKG_BUILD/IMG_BUILD/DEPLOY/CHECK/AUTO_TEST/ROLLBACK',
  batch_no       INT          NOT NULL DEFAULT 0 COMMENT 'production deploy batch (0 for non-prod tasks)',
  target_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'DE id, or check-type code for VERIFY/CHECK',
  status         VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/WAITING_EXTERNAL/SUCCESS/FAILED/TIMEOUT/SKIPPED',
  attempt        INT          NOT NULL DEFAULT 0,
  external_id    VARCHAR(128) NULL COMMENT 'CODING side id',
  external_url   VARCHAR(512) NULL,
  result_summary VARCHAR(512) NULL,
  deadline_at    DATETIME(3)  NULL,
  started_at     DATETIME(3)  NULL,
  finished_at    DATETIME(3)  NULL,
  duration_ms    BIGINT       NULL,
  UNIQUE KEY uk_idem (instance_id, node_type, task_type, batch_no, target_id, attempt),
  KEY idx_external (external_id),
  KEY idx_status_deadline (status, deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deploy_unit_lock (
  unit_id     BIGINT PRIMARY KEY,
  instance_id BIGINT      NOT NULL,
  acquired_at DATETIME(3) NOT NULL,
  expire_at   DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;