-- Dev-only demo state for the P6-G 单 DE 重部署 + 任务日志 screens.
--
-- Seeds exactly one release marked by its business numbers, so the script can delete its own rows
-- and re-run:
--
--   * PO-DEMO-REDEPLOY / PL-DEMO-REDEPLOY: item 92101 is FAILED (both gate dimensions passed
--     earlier), the order mirrors it, and the pipeline instance FAILED at PROD_DEPLOY with the
--     image/commit reusable. Production batch 1 succeeded; batch 2 (a second PROD DE this seed
--     adds to unit 2) failed, so the release page shows a FAILED DEPLOY task row that 单 DE 重部署
--     accepts and a SUCCESS batch-1 row it must refuse.
--   * The build/test tasks carry external ids, so the 日志 proxy has a job to address (with no
--     CODING connection configured the engine answers ENGINE_UNAVAILABLE, which the drawer must
--     render as the server message).
--
-- The script is idempotent: it deletes exactly the rows of its own markers (children first) and
-- inserts them again. It also creates dev (ADMIN), ops-lead (OPS + OPS_LEAD) and p6g-viewer (TEST,
-- no control:execute-prod) idempotently: the probe needs an operator and a caller that must NOT
-- see an enabled 重新部署 button. No existing demo order is modified; the extra batch-2 DE is
-- likewise owned by this seed (it is deleted before the re-insert).
--
-- The probe (scripts/verify-p6g-ui.mjs) performs one real redeploy and re-runs this script
-- afterwards, so later screenshots of other slices keep seeing the failed-batch state.
--
-- Audit rows created by a real redeploy are left as evidence.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-redeploy.sql"
--
-- English-only content on purpose: this file is piped through the mysql client, which on this
-- Windows host mangles Chinese in --execute payloads.

SET @publish := 'PO-DEMO-REDEPLOY';
SET @pipeline := 'PL-DEMO-REDEPLOY';
SET @item := 92101;
SET @order := 92101;
SET @instance := 92101;
SET @task := 92101;

-- 1. Remove only the rows this script created on a previous run (children first). A real probe
--    redeploy creates another instance under the same order with its own pipeline number, so the
--    cleanup follows the order, not just the marker pipeline number.
DELETE nt FROM node_task nt
  JOIN pipeline_instance pi ON pi.id = nt.instance_id
 WHERE pi.order_id = @order;

DELETE pn FROM pipeline_node pn
  JOIN pipeline_instance pi ON pi.id = pn.instance_id
 WHERE pi.order_id = @order;

DELETE FROM deploy_unit_lock
 WHERE instance_id IN (SELECT id FROM pipeline_instance WHERE order_id = @order)
    OR unit_id = 2;

DELETE FROM pipeline_instance WHERE order_id = @order OR pipeline_no = @pipeline;

DELETE FROM release_order WHERE publish_no = @publish OR id = @order;

DELETE FROM release_item WHERE id = @item OR crid LIKE 'CR-DEMO-REDEPLOY%';

DELETE FROM release_task_member WHERE task_id = @task;

DELETE FROM release_task WHERE id = @task OR name = 'DEMO-REDEPLOY';

-- The seed's own second PROD DE (batch 2); re-created below so a re-run stays deterministic.
DELETE FROM env_de WHERE unit_id = 2 AND de_name = 'demo-prod-b';

-- 2. The identities the probe switches between (idempotent).
INSERT IGNORE INTO app_user (id, name, status, created_at) VALUES
  ('dev', 'Dev User', 'ACTIVE', UTC_TIMESTAMP(3)),
  ('ops-lead', 'Ops Lead', 'ACTIVE', UTC_TIMESTAMP(3)),
  ('p6g-viewer', 'P6G Viewer', 'ACTIVE', UTC_TIMESTAMP(3));

INSERT IGNORE INTO user_role (user_id, role) VALUES
  ('dev', 'ADMIN'),
  ('ops-lead', 'OPS'),
  ('ops-lead', 'OPS_LEAD'),
  ('p6g-viewer', 'TEST');

INSERT IGNORE INTO user_scope (user_id, scope_type, scope_id) VALUES
  ('dev', 'ALL', 0),
  ('ops-lead', 'ALL', 0),
  ('p6g-viewer', 'ALL', 0);

-- 3. The batch-2 production DE the failed task row points at (batch 1 uses the existing one).
SET @de1 := COALESCE(
  (SELECT id FROM env_de WHERE unit_id = 2 AND env_kind = 'PROD' AND deploy_batch_no = 1
   ORDER BY id LIMIT 1), 0);
INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no, created_at, updated_at)
VALUES (2, 'PROD', 'demo-prod-b', 2, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
SET @de2 := (SELECT id FROM env_de WHERE unit_id = 2 AND de_name = 'demo-prod-b');

-- 4. The demo task on subsystem 2 (the same fixture the earlier seeds use).
INSERT INTO release_task
  (id, subsystem_id, name, creator, purpose, plan_finish_date, status, created_at, updated_at)
VALUES
  (@task, 2, 'DEMO-REDEPLOY', 'dev',
   'Demo task for the P6-G redeploy-and-logs probe', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, UTC_TIMESTAMP(3) - INTERVAL 1 HOUR);

-- 5. The change item: FAILED after the production batch failed; both gates had passed.
INSERT INTO release_item
  (id, task_id, crid, type, unit_id, build_plan_id, source_branch, module_path, version,
   plan_release_date, status, gate_status, test_status, base_commit, last_test_pass_by,
   last_test_pass_at, test_fail_reason, row_version, created_at, updated_at)
VALUES
  (@item, @task, 'CR-DEMO-REDEPLOY', 'UNIT', 2, 'demo-build', 'release/demo-redeploy', NULL,
   'V0.0.1-demo-redeploy', CURDATE(), 'FAILED', 'CHECK_PASS', 'TEST_PASS',
   '4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b', 'dev',
   UTC_TIMESTAMP(3) - INTERVAL 80 MINUTE, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE);

-- 6. The release order mirrors the failed item.
INSERT INTO release_order
  (id, publish_no, item_id, oa_ticket_no, planned_release_time, window_result, deadline_at,
   status, row_version, created_at, executed_by)
VALUES
  (@order, @publish, @item, 'OA-DEMO-REDEPLOY', UTC_TIMESTAMP(3) - INTERVAL 60 MINUTE, 'OUT',
   NULL, 'FAILED', 0, UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, NULL);

-- 7. The pipeline instance: FAILED at the production node with a reusable image and commit.
SET @start := UTC_TIMESTAMP(3) - INTERVAL 90 MINUTE;
SET @failed := UTC_TIMESTAMP(3) - INTERVAL 20 MINUTE;

INSERT INTO pipeline_instance
  (id, order_id, pipeline_no, exec_type, trigger_type, current_node, status, status_reason,
   image_digest, commit_sha, start_at, end_at, created_at, test_ready_at, paused_at,
   item_id, unit_id, requested_by)
VALUES
  (@instance, @order, @pipeline, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'FAILED',
   'demo: production batch 2 failed on the second DE',
   'sha256:4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b',
   '4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b', @start, @failed, @start,
   @start + INTERVAL 50 MINUTE, NULL, @item, 2, 'dev');

-- 8. Nodes: BUILD/TEST_DEPLOY/VERIFY passed; PROD_DEPLOY failed after batch 1 succeeded.
INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at, finished_at) VALUES
  (@instance, 'BUILD', 0, 'SUCCESS', @start, @start + INTERVAL 8 MINUTE),
  (@instance, 'TEST_DEPLOY', 1, 'SUCCESS', @start + INTERVAL 8 MINUTE,
   @start + INTERVAL 13 MINUTE),
  (@instance, 'VERIFY', 2, 'SUCCESS', @start + INTERVAL 13 MINUTE,
   @start + INTERVAL 15 MINUTE),
  (@instance, 'PROD_DEPLOY', 3, 'FAILED', @start + INTERVAL 15 MINUTE, @failed);

-- 9. Task rows: build/test/prod-batch-1 succeeded with engine job ids; prod batch 2 failed.
SET @test_de := COALESCE(
  (SELECT id FROM env_de WHERE unit_id = 2 AND env_kind = 'TEST' ORDER BY id LIMIT 1), 0);

INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   external_id, external_url, result_summary, deadline_at, started_at, finished_at, duration_ms,
   updated_at, query_failures)
VALUES
  (@instance, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 1, 'demo-redeploy-check-1', NULL,
   'code check passed', NULL, @start, @start + INTERVAL 2 MINUTE, 120000,
   @start + INTERVAL 2 MINUTE, 0),
  (@instance, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-redeploy-pkg-1', NULL,
   'package built', NULL, @start + INTERVAL 2 MINUTE, @start + INTERVAL 5 MINUTE, 180000,
   @start + INTERVAL 5 MINUTE, 0),
  (@instance, 'BUILD', 'IMG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-redeploy-img-1', NULL,
   'image built: sha256:4a5b6c7d8e9f', NULL, @start + INTERVAL 5 MINUTE,
   @start + INTERVAL 8 MINUTE, 180000, @start + INTERVAL 8 MINUTE, 0),
  (@instance, 'TEST_DEPLOY', 'DEPLOY', 0, @test_de, 'SUCCESS', 1,
   'demo-redeploy-test-1', NULL, 'deployed to demo-sit', NULL, @start + INTERVAL 8 MINUTE,
   @start + INTERVAL 13 MINUTE, 300000, @start + INTERVAL 13 MINUTE, 0),
  (@instance, 'PROD_DEPLOY', 'DEPLOY', 1, @de1, 'SUCCESS', 1,
   'demo-redeploy-prod-1', NULL, 'deployed to demo-prod', NULL,
   @start + INTERVAL 15 MINUTE, @start + INTERVAL 25 MINUTE, 600000,
   @start + INTERVAL 25 MINUTE, 0),
  (@instance, 'PROD_DEPLOY', 'DEPLOY', 2, @de2, 'FAILED', 1,
   'demo-redeploy-prod-2', NULL, 'engine reported FAILED on demo-prod-b', NULL,
   @start + INTERVAL 25 MINUTE, @failed, 300000, @failed, 0);

SELECT @publish AS publish_no, @pipeline AS pipeline_no, @item AS item_id,
       @order AS order_id, @instance AS instance_id, @de1 AS batch1_de, @de2 AS batch2_de;
