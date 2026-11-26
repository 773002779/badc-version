-- Dev-only demo state for the P6-F 批量发布 probe (PART-02).
--
-- Seeds one ACTIVE task on subsystem 1 with exactly three items, so the task-detail page shows a
-- selectable batch:
--
--   * 92102 PENDING on unit 1 (full bindings + TEST/PROD DEs): the publish succeeds;
--   * 92103 PENDING on unit 91002 (demo-sdk-consumer, no coding_task_binding rows): the publish
--     is refused by the server, so the batch continues past a real failure;
--   * 92104 DONE on unit 1: the selection checkbox must stay disabled (design §5.4).
--
-- The script is idempotent: it deletes exactly the rows of its own markers (children first) and
-- inserts them again. It never touches the other demo orders or the P6-G seed rows.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-batchpublish.sql"
--
-- English-only content on purpose: this file is piped through the mysql client, which on this
-- Windows host mangles Chinese in --execute payloads.

SET @task := 92102;
SET @item_ok := 92102;
SET @item_fail := 92103;
SET @item_done := 92104;

-- 1. Remove only the rows this script created on a previous run (children first).
DELETE FROM release_item WHERE id IN (@item_ok, @item_fail, @item_done)
   OR crid LIKE 'CR-DEMO-BATCH%';

DELETE FROM release_task_member WHERE task_id = @task;

DELETE FROM release_task WHERE id = @task OR name = 'DEMO-BATCH';

-- 2. The caller the probe runs as (idempotent; ADMIN has release:publish).
INSERT IGNORE INTO app_user (id, name, status, created_at) VALUES
  ('dev', 'Dev User', 'ACTIVE', UTC_TIMESTAMP(3));

INSERT IGNORE INTO user_role (user_id, role) VALUES ('dev', 'ADMIN');
INSERT IGNORE INTO user_scope (user_id, scope_type, scope_id) VALUES ('dev', 'ALL', 0);

-- 3. The demo task on subsystem 1 (the fixture the earlier seeds use).
INSERT INTO release_task
  (id, subsystem_id, name, creator, purpose, plan_finish_date, status, created_at, updated_at)
VALUES
  (@task, 1, 'DEMO-BATCH', 'dev',
   'Demo task for the P6-F batch-publish probe', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, UTC_TIMESTAMP(3));

-- 4. The three items: publishable / publishable-but-unconfigured / done.
INSERT INTO release_item
  (id, task_id, crid, type, unit_id, build_plan_id, source_branch, module_path, version,
   plan_release_date, status, gate_status, test_status, row_version, created_at, updated_at)
VALUES
  (@item_ok, @task, 'CR-DEMO-BATCH-1', 'UNIT', 1, 'demo-build', 'release/demo-batch-1', NULL,
   'V0.0.1-demo-batch-1', CURDATE(), 'PENDING', 'CHECK_RUNNING', 'TEST_PENDING', 0,
   UTC_TIMESTAMP(3) - INTERVAL 50 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 50 MINUTE),
  (@item_fail, @task, 'CR-DEMO-BATCH-2', 'UNIT', 91002, 'demo-build', 'release/demo-batch-2', NULL,
   'V0.0.1-demo-batch-2', CURDATE(), 'PENDING', 'CHECK_RUNNING', 'TEST_PENDING', 0,
   UTC_TIMESTAMP(3) - INTERVAL 50 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 50 MINUTE),
  (@item_done, @task, 'CR-DEMO-BATCH-3', 'UNIT', 1, 'demo-build', 'release/demo-batch-3', NULL,
   'V0.0.1-demo-batch-3', CURDATE(), 'DONE', 'CHECK_PASS', 'TEST_PASS', 0,
   UTC_TIMESTAMP(3) - INTERVAL 2 DAY, UTC_TIMESTAMP(3) - INTERVAL 1 DAY);

SELECT @task AS task_id, @item_ok AS ok_item, @item_fail AS fail_item, @item_done AS done_item;
