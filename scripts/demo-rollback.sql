-- Dev-only demo state for the P4 rollback drawer and the global records page.
--
-- The demo unit gets a small production version history: two fully successful forward
-- executions with different image digests. The newer one becomes the current online version,
-- the older one becomes the single rollback target. Two extra release orders also give the
-- /records page more than one row to filter and page through.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-rollback.sql"
--
-- Idempotent: every row this file creates carries a demo marker (CR-DEMO-RB-*, PO-DEMO-RB-*,
-- PL-DEMO-RB-*, sha256:demo-rb-*); re-running deletes exactly those rows first and inserts them
-- again. It never touches the existing demo release PO-20261121-5976BF, its item/order, the
-- unit's env DE rows or the unit's CODING bindings.
--
-- English-only comments on purpose: this file is piped through the mysql client, which on this
-- Windows host mangles Chinese in --execute payloads.

SET @unit := 2;
SET @task := 2;
SET @newerDigest := 'sha256:demo-rb-9f1c4e7a2b6d8f0c3e5a1b7d9f2c4e6a8b0d3f5c7a9e1b4d6f8c0a2e4b6d8f0c';
SET @olderDigest := 'sha256:demo-rb-4b2e6d8f0a1c3e5b7d9f2a4c6e8b0d1f3a5c7e9b2d4f6a8c0e1b3d5f7a9c2e4b';
SET @newerCommit := '9f1c4e7a2b6d8f0c3e5a1b7d9f2c4e6a8b0d3f5c';
SET @olderCommit := '4b2e6d8f0a1c3e5b7d9f2a4c6e8b0d1f3a5c7e9b';

-- 1. Remove only the rows this script created on a previous run (children first).
DELETE nt FROM node_task nt
  JOIN pipeline_instance pi ON pi.id = nt.instance_id
 WHERE pi.pipeline_no IN ('PL-DEMO-RB-NEW', 'PL-DEMO-RB-OLD');
DELETE pn FROM pipeline_node pn
  JOIN pipeline_instance pi ON pi.id = pn.instance_id
 WHERE pi.pipeline_no IN ('PL-DEMO-RB-NEW', 'PL-DEMO-RB-OLD');
DELETE FROM rollback_record
 WHERE source_publish_no IN ('PO-DEMO-RB-NEW', 'PO-DEMO-RB-OLD')
    OR target_publish_no IN ('PO-DEMO-RB-NEW', 'PO-DEMO-RB-OLD');
DELETE FROM pipeline_instance
 WHERE pipeline_no IN ('PL-DEMO-RB-NEW', 'PL-DEMO-RB-OLD');
DELETE FROM release_order
 WHERE publish_no IN ('PO-DEMO-RB-NEW', 'PO-DEMO-RB-OLD');
DELETE FROM release_item
 WHERE crid IN ('CR-DEMO-RB-NEW', 'CR-DEMO-RB-OLD');

-- 2. The historical change items of the same task/unit (unique per branch).
INSERT INTO release_item
  (task_id, crid, type, unit_id, build_plan_id, source_branch, module_path, version,
   plan_release_date, status, gate_status, test_status, base_commit, row_version,
   created_at, updated_at)
VALUES
  (@task, 'CR-DEMO-RB-OLD', 'UNIT', @unit, 'demo-build', 'release/20261118', 'demo-api',
   'V0.0.8-demo', UTC_DATE() - INTERVAL 4 DAY, 'DONE', 'CHECK_PASS', 'TEST_PASS', NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 3 DAY, UTC_TIMESTAMP(3) - INTERVAL 3 DAY);
SET @olderItem := LAST_INSERT_ID();

INSERT INTO release_item
  (task_id, crid, type, unit_id, build_plan_id, source_branch, module_path, version,
   plan_release_date, status, gate_status, test_status, base_commit, row_version,
   created_at, updated_at)
VALUES
  (@task, 'CR-DEMO-RB-NEW', 'UNIT', @unit, 'demo-build', 'release/20261119', 'demo-api',
   'V0.0.9-demo', UTC_DATE() - INTERVAL 2 DAY, 'DONE', 'CHECK_PASS', 'TEST_PASS', NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 1 DAY, UTC_TIMESTAMP(3) - INTERVAL 1 DAY);
SET @newerItem := LAST_INSERT_ID();

-- 3. Their release orders (DONE, with an operator for the records page).
INSERT INTO release_order
  (publish_no, item_id, oa_ticket_no, planned_release_time, window_result, deadline_at,
   status, row_version, created_at, executed_by)
VALUES
  ('PO-DEMO-RB-OLD', @olderItem, 'OA-DEMO-RB-OLD',
   UTC_TIMESTAMP(3) - INTERVAL 3 DAY, NULL, NULL, 'DONE', 0,
   UTC_TIMESTAMP(3) - INTERVAL 3 DAY, 'dev');
SET @olderOrder := LAST_INSERT_ID();

INSERT INTO release_order
  (publish_no, item_id, oa_ticket_no, planned_release_time, window_result, deadline_at,
   status, row_version, created_at, executed_by)
VALUES
  ('PO-DEMO-RB-NEW', @newerItem, 'OA-DEMO-RB-NEW',
   UTC_TIMESTAMP(3) - INTERVAL 1 DAY, NULL, NULL, 'DONE', 0,
   UTC_TIMESTAMP(3) - INTERVAL 1 DAY, 'dev');
SET @newerOrder := LAST_INSERT_ID();

-- 4. Two completed ONLINE executions, newest finished two hours ago.
SET @olderEnd := UTC_TIMESTAMP(3) - INTERVAL 2 DAY;
SET @olderStart := @olderEnd - INTERVAL 40 MINUTE;
SET @newerEnd := UTC_TIMESTAMP(3) - INTERVAL 2 HOUR;
SET @newerStart := @newerEnd - INTERVAL 35 MINUTE;

INSERT INTO pipeline_instance
  (order_id, pipeline_no, exec_type, trigger_type, current_node, status, status_reason,
   image_digest, commit_sha, start_at, end_at, created_at, test_ready_at)
VALUES
  (@olderOrder, 'PL-DEMO-RB-OLD', 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'DONE', NULL,
   @olderDigest, @olderCommit, @olderStart, @olderEnd, @olderStart, @olderEnd - INTERVAL 30 MINUTE);
SET @olderInst := LAST_INSERT_ID();

INSERT INTO pipeline_instance
  (order_id, pipeline_no, exec_type, trigger_type, current_node, status, status_reason,
   image_digest, commit_sha, start_at, end_at, created_at, test_ready_at)
VALUES
  (@newerOrder, 'PL-DEMO-RB-NEW', 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'DONE', NULL,
   @newerDigest, @newerCommit, @newerStart, @newerEnd, @newerStart, @newerEnd - INTERVAL 25 MINUTE);
SET @newerInst := LAST_INSERT_ID();

-- 5. A full four-node pipeline per execution; the production node is SUCCESS.
INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at, finished_at)
VALUES
  (@olderInst, 'BUILD', 0, 'SUCCESS', @olderStart, @olderStart + INTERVAL 8 MINUTE),
  (@olderInst, 'TEST_DEPLOY', 1, 'SUCCESS', @olderStart + INTERVAL 8 MINUTE, @olderStart + INTERVAL 14 MINUTE),
  (@olderInst, 'VERIFY', 2, 'SUCCESS', @olderStart + INTERVAL 14 MINUTE, @olderStart + INTERVAL 30 MINUTE),
  (@olderInst, 'PROD_DEPLOY', 3, 'SUCCESS', @olderStart + INTERVAL 30 MINUTE, @olderEnd),
  (@newerInst, 'BUILD', 0, 'SUCCESS', @newerStart, @newerStart + INTERVAL 6 MINUTE),
  (@newerInst, 'TEST_DEPLOY', 1, 'SUCCESS', @newerStart + INTERVAL 6 MINUTE, @newerStart + INTERVAL 11 MINUTE),
  (@newerInst, 'VERIFY', 2, 'SUCCESS', @newerStart + INTERVAL 11 MINUTE, @newerStart + INTERVAL 25 MINUTE),
  (@newerInst, 'PROD_DEPLOY', 3, 'SUCCESS', @newerStart + INTERVAL 25 MINUTE, @newerEnd);

-- 6. Build tasks, the test deploy, and one DEPLOY task per production DE. The production
--    DE rows of the unit are read, never modified: their batch numbers stay as configured.
INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   started_at, finished_at, duration_ms, updated_at)
VALUES
  (@olderInst, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 0, @olderStart, @olderStart + INTERVAL 2 MINUTE, 120000, @olderStart + INTERVAL 2 MINUTE),
  (@olderInst, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 0, @olderStart + INTERVAL 2 MINUTE, @olderStart + INTERVAL 6 MINUTE, 240000, @olderStart + INTERVAL 6 MINUTE),
  (@olderInst, 'BUILD', 'IMG_BUILD', 0, 0, 'SUCCESS', 0, @olderStart + INTERVAL 6 MINUTE, @olderStart + INTERVAL 8 MINUTE, 120000, @olderStart + INTERVAL 8 MINUTE),
  (@newerInst, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 0, @newerStart, @newerStart + INTERVAL 2 MINUTE, 120000, @newerStart + INTERVAL 2 MINUTE),
  (@newerInst, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 0, @newerStart + INTERVAL 2 MINUTE, @newerStart + INTERVAL 5 MINUTE, 180000, @newerStart + INTERVAL 5 MINUTE),
  (@newerInst, 'BUILD', 'IMG_BUILD', 0, 0, 'SUCCESS', 0, @newerStart + INTERVAL 5 MINUTE, @newerStart + INTERVAL 6 MINUTE, 60000, @newerStart + INTERVAL 6 MINUTE);

INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   started_at, finished_at, duration_ms, updated_at)
SELECT @olderInst, 'TEST_DEPLOY', 'DEPLOY', ed.deploy_batch_no, ed.id, 'SUCCESS', 0,
       @olderStart + INTERVAL 8 MINUTE, @olderStart + INTERVAL 14 MINUTE, 360000,
       @olderStart + INTERVAL 14 MINUTE
  FROM env_de ed
 WHERE ed.unit_id = @unit AND ed.env_kind = 'TEST'
 ORDER BY ed.id
 LIMIT 1;

INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   started_at, finished_at, duration_ms, updated_at)
SELECT @newerInst, 'TEST_DEPLOY', 'DEPLOY', ed.deploy_batch_no, ed.id, 'SUCCESS', 0,
       @newerStart + INTERVAL 6 MINUTE, @newerStart + INTERVAL 11 MINUTE, 300000,
       @newerStart + INTERVAL 11 MINUTE
  FROM env_de ed
 WHERE ed.unit_id = @unit AND ed.env_kind = 'TEST'
 ORDER BY ed.id
 LIMIT 1;

INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   started_at, finished_at, duration_ms, updated_at)
SELECT @olderInst, 'PROD_DEPLOY', 'DEPLOY', ed.deploy_batch_no, ed.id, 'SUCCESS', 0,
       @olderStart + INTERVAL 30 MINUTE, @olderEnd, 600000, @olderEnd
  FROM env_de ed
 WHERE ed.unit_id = @unit AND ed.env_kind IN ('PROD', 'DR', 'BLUE')
 ORDER BY ed.deploy_batch_no, ed.id;

INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   started_at, finished_at, duration_ms, updated_at)
SELECT @newerInst, 'PROD_DEPLOY', 'DEPLOY', ed.deploy_batch_no, ed.id, 'SUCCESS', 0,
       @newerStart + INTERVAL 25 MINUTE, @newerEnd, 600000, @newerEnd
  FROM env_de ed
 WHERE ed.unit_id = @unit AND ed.env_kind IN ('PROD', 'DR', 'BLUE')
 ORDER BY ed.deploy_batch_no, ed.id;

SELECT @unit AS unit_id,
       @newerDigest AS current_digest,
       @olderDigest AS rollback_target_digest,
       @newerOrder AS newest_order,
       @olderOrder AS older_order;