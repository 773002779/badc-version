-- Dev-only demo state for the P5-B stop / exit / close / archive screens.
--
-- Seeds three demo objects, all marked by their business numbers or names:
--
--   * PO-DEMO-CTL-STOP: an online release whose item is PROD_DEPLOYING and whose pipeline is
--     PAUSED at the PROD_DEPLOY node. Production batch 1 is SUCCESS (already online, must be kept
--     by a stop) and batch 2 is PENDING (the cascade cancels it). Unit 2's lock is held by that
--     instance, so a stop also releases it. Stop it from the release page or POST .../stop.
--   * DEMO-CTL-ARCHIVE-A and DEMO-CTL-ARCHIVE-B: one task per probe theme. Each has a DONE item,
--     a TESTING item with a RUNNING pipeline (one in-flight instance for the archive cascade) and
--     a GATE_PENDING item that the probe closes through the UI before archiving. Re-running this
--     script resets both tasks to ACTIVE.
--
-- It also grants the OPS role to ops-lead idempotently: the UI probe needs a caller that has
-- control:execute-prod but not release:publish (design section 4). No existing demo order is
-- modified; in particular PO-20261121-5976BF is never touched.
--
-- The script is idempotent: it deletes exactly the rows of its own markers (children first) and
-- inserts them again. Audit and notification rows created by real actions are left as evidence.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-control.sql"
--
-- English-only content on purpose: this file is piped through the mysql client, which on this
-- Windows host mangles Chinese in --execute payloads.

SET @stopPublish := 'PO-DEMO-CTL-STOP';
SET @stopPipeline := 'PL-DEMO-CTL-STOP';
SET @archAPublish := 'PO-DEMO-CTL-ARCH-A';
SET @archAPipeline := 'PL-DEMO-CTL-ARCH-A';
SET @archBPublish := 'PO-DEMO-CTL-ARCH-B';
SET @archBPipeline := 'PL-DEMO-CTL-ARCH-B';

-- 1. Remove only the rows this script created on a previous run (children first).
DELETE nt FROM node_task nt
  JOIN pipeline_instance pi ON pi.id = nt.instance_id
 WHERE pi.pipeline_no IN (@stopPipeline, @archAPipeline, @archBPipeline);

DELETE pn FROM pipeline_node pn
  JOIN pipeline_instance pi ON pi.id = pn.instance_id
 WHERE pi.pipeline_no IN (@stopPipeline, @archAPipeline, @archBPipeline);

DELETE FROM deploy_unit_lock
 WHERE instance_id IN (SELECT id FROM pipeline_instance
                        WHERE pipeline_no IN (@stopPipeline, @archAPipeline, @archBPipeline));

DELETE FROM pipeline_instance
 WHERE pipeline_no IN (@stopPipeline, @archAPipeline, @archBPipeline);

DELETE FROM approval
 WHERE order_id IN (SELECT id FROM release_order
                     WHERE publish_no IN (@stopPublish, @archAPublish, @archBPublish));

DELETE FROM admission_snapshot
 WHERE item_id IN (90001, 90011, 90012, 90013, 90021, 90022, 90023);

DELETE FROM release_order
 WHERE publish_no IN (@stopPublish, @archAPublish, @archBPublish);

DELETE FROM release_item
 WHERE id IN (90001, 90011, 90012, 90013, 90021, 90022, 90023);

DELETE FROM release_task WHERE id IN (90001, 90002);

-- 2. The control-capable non-publisher identity the probe uses (idempotent; adds no permission
--    beyond OPS, which never carries release:publish).
INSERT IGNORE INTO user_role (user_id, role) VALUES ('ops-lead', 'OPS');

-- 3. The two archive demo tasks (light/dark probe runs).
INSERT INTO release_task
  (id, subsystem_id, name, creator, purpose, plan_finish_date, status, created_at, updated_at)
VALUES
  (90001, 2, 'DEMO-CTL-ARCHIVE-A', 'dev',
   'Demo task for the P5-B archive probe (light theme)', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (90002, 2, 'DEMO-CTL-ARCHIVE-B', 'dev',
   'Demo task for the P5-B archive probe (dark theme)', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 4. The stop release item plus the archive-task items (unit 2 of subsystem 2).
INSERT INTO release_item
  (id, task_id, crid, type, unit_id, build_plan_id, source_branch, module_path, version,
   plan_release_date, status, gate_status, test_status, base_commit, last_test_pass_by,
   last_test_pass_at, test_fail_reason, row_version, created_at, updated_at)
VALUES
  (90001, 2, 'CR-DEMO-CTL-STOP', 'UNIT', 2, 'demo-build', 'release/demo-ctl-stop', NULL,
   'V0.0.1-demo-ctl', CURDATE(), 'PROD_DEPLOYING', 'CHECK_PASS', 'TEST_PASS', NULL, '01388007',
   UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, NULL, 0, UTC_TIMESTAMP(3) - INTERVAL 2 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 1 HOUR),
  (90011, 90001, 'CR-DEMO-CTL-ARCH-A-DONE', 'UNIT', 2, 'demo-build',
   'release/demo-ctl-a-done', NULL, 'V0.0.1-demo-a', CURDATE() - INTERVAL 1 DAY, 'DONE',
   'CHECK_PASS', 'TEST_PASS', NULL, '01388007', UTC_TIMESTAMP(3) - INTERVAL 2 DAY, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 2 DAY, UTC_TIMESTAMP(3) - INTERVAL 1 DAY),
  (90012, 90001, 'CR-DEMO-CTL-ARCH-A-OPEN', 'UNIT', 2, 'demo-build',
   'release/demo-ctl-a-open', NULL, 'V0.0.2-demo-a', CURDATE(), 'TESTING',
   'CHECK_PASS', 'TEST_PENDING', NULL, NULL, NULL, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE),
  (90013, 90001, 'CR-DEMO-CTL-ARCH-A-CLOSE', 'UNIT', 2, 'demo-build',
   'release/demo-ctl-a-close', NULL, 'V0.0.3-demo-a', CURDATE(), 'GATE_PENDING',
   'CHECK_PASS', 'TEST_PASS', NULL, '01388007', UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE),
  (90021, 90002, 'CR-DEMO-CTL-ARCH-B-DONE', 'UNIT', 2, 'demo-build',
   'release/demo-ctl-b-done', NULL, 'V0.0.1-demo-b', CURDATE() - INTERVAL 1 DAY, 'DONE',
   'CHECK_PASS', 'TEST_PASS', NULL, '01388007', UTC_TIMESTAMP(3) - INTERVAL 2 DAY, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 2 DAY, UTC_TIMESTAMP(3) - INTERVAL 1 DAY),
  (90022, 90002, 'CR-DEMO-CTL-ARCH-B-OPEN', 'UNIT', 2, 'demo-build',
   'release/demo-ctl-b-open', NULL, 'V0.0.2-demo-b', CURDATE(), 'TESTING',
   'CHECK_PASS', 'TEST_PENDING', NULL, NULL, NULL, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE),
  (90023, 90002, 'CR-DEMO-CTL-ARCH-B-CLOSE', 'UNIT', 2, 'demo-build',
   'release/demo-ctl-b-close', NULL, 'V0.0.3-demo-b', CURDATE(), 'GATE_PENDING',
   'CHECK_PASS', 'TEST_PASS', NULL, '01388007', UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE);

-- 5. The release orders: the stoppable one is EXECUTING; the unfinished archive items are TESTING.
INSERT INTO release_order
  (id, publish_no, item_id, oa_ticket_no, planned_release_time, window_result, deadline_at,
   status, row_version, created_at, executed_by)
VALUES
  (90001, @stopPublish, 90001, 'OA-DEMO-CTL-STOP', UTC_TIMESTAMP(3) - INTERVAL 20 MINUTE, 'IN',
   NULL, 'EXECUTING', 0, UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE, 'dev'),
  (90012, @archAPublish, 90012, NULL, NULL, NULL, NULL, 'TESTING', 0,
   UTC_TIMESTAMP(3) - INTERVAL 40 MINUTE, NULL),
  (90022, @archBPublish, 90022, NULL, NULL, NULL, NULL, 'TESTING', 0,
   UTC_TIMESTAMP(3) - INTERVAL 40 MINUTE, NULL);

-- 6. The pipeline instances: stop is PAUSED at PROD_DEPLOY; the archive items are RUNNING at
--    TEST_DEPLOY (one in-flight instance each to terminate).
SET @stopStart := UTC_TIMESTAMP(3) - INTERVAL 35 MINUTE;
SET @stopPaused := UTC_TIMESTAMP(3) - INTERVAL 5 MINUTE;
SET @archStart := UTC_TIMESTAMP(3) - INTERVAL 20 MINUTE;

INSERT INTO pipeline_instance
  (id, order_id, pipeline_no, exec_type, trigger_type, current_node, status, status_reason,
   image_digest, commit_sha, start_at, end_at, created_at, test_ready_at, paused_at,
   item_id, unit_id, requested_by)
VALUES
  (90001, 90001, @stopPipeline, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'PAUSED',
   'paused at the production batch boundary (demo control)',
   'sha256:demo-ctl-3c9e5a7b1d4f2a6c8e0b3d5f7a9c1e4b6d8f0a2c4e6b9d1f',
   '3c9e5a7b1d4f2a6c8e0b3d5f7a9c1e4b6d8f0a2c', @stopStart, NULL, @stopStart,
   @stopStart + INTERVAL 15 MINUTE, @stopPaused, NULL, NULL, NULL),
  (90012, 90012, @archAPipeline, 'ONLINE', 'INITIAL', 'TEST_DEPLOY', 'RUNNING', NULL,
   NULL, NULL, @archStart, NULL, @archStart, NULL, NULL, NULL, NULL, NULL),
  (90022, 90022, @archBPipeline, 'ONLINE', 'INITIAL', 'TEST_DEPLOY', 'RUNNING', NULL,
   NULL, NULL, @archStart, NULL, @archStart, NULL, NULL, NULL, NULL, NULL);

-- 7. Nodes. The stop pipeline has finished BUILD/TEST_DEPLOY/VERIFY and is RUNNING at
--    PROD_DEPLOY; the archive pipelines are RUNNING at TEST_DEPLOY.
INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at, finished_at)
VALUES
  (90001, 'BUILD', 0, 'SUCCESS', @stopStart, @stopStart + INTERVAL 8 MINUTE),
  (90001, 'TEST_DEPLOY', 1, 'SUCCESS', @stopStart + INTERVAL 8 MINUTE,
   @stopStart + INTERVAL 13 MINUTE),
  (90001, 'VERIFY', 2, 'SUCCESS', @stopStart + INTERVAL 13 MINUTE,
   @stopStart + INTERVAL 15 MINUTE),
  (90001, 'PROD_DEPLOY', 3, 'RUNNING', @stopStart + INTERVAL 15 MINUTE, NULL),
  (90012, 'BUILD', 0, 'SUCCESS', @archStart, @archStart + INTERVAL 6 MINUTE),
  (90012, 'TEST_DEPLOY', 1, 'RUNNING', @archStart + INTERVAL 6 MINUTE, NULL),
  (90012, 'VERIFY', 2, 'PENDING', NULL, NULL),
  (90012, 'PROD_DEPLOY', 3, 'PENDING', NULL, NULL),
  (90022, 'BUILD', 0, 'SUCCESS', @archStart, @archStart + INTERVAL 6 MINUTE),
  (90022, 'TEST_DEPLOY', 1, 'RUNNING', @archStart + INTERVAL 6 MINUTE, NULL),
  (90022, 'VERIFY', 2, 'PENDING', NULL, NULL),
  (90022, 'PROD_DEPLOY', 3, 'PENDING', NULL, NULL);

-- 8. The deploy target of unit 2 (daily falls back to TEST when no DAILY DE exists).
SET @de := COALESCE(
  (SELECT id FROM env_de WHERE unit_id = 2 AND env_kind = 'DAILY' ORDER BY id LIMIT 1),
  (SELECT id FROM env_de WHERE unit_id = 2 AND env_kind = 'TEST' ORDER BY id LIMIT 1),
  0
);
SET @batch := COALESCE((SELECT deploy_batch_no FROM env_de WHERE id = @de), 1);

-- 9. Tasks: BUILD evidence, batch 1 online (SUCCESS, kept by a stop) and batch 2 PENDING (cancelled).
INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   external_id, external_url, result_summary, deadline_at, started_at, finished_at, duration_ms,
   updated_at, query_failures)
VALUES
  (90001, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 1, 'demo-ctl-check-1',
   'https://coding.example.com/p/demo-release/build/demo-check/logs', 'code check passed',
   NULL, @stopStart, @stopStart + INTERVAL 2 MINUTE, 120000,
   @stopStart + INTERVAL 2 MINUTE, 0),
  (90001, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-ctl-pkg-1', NULL,
   'package built', NULL, @stopStart + INTERVAL 2 MINUTE, @stopStart + INTERVAL 5 MINUTE,
   180000, @stopStart + INTERVAL 5 MINUTE, 0),
  (90001, 'BUILD', 'IMG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-ctl-img-1',
   'https://coding.example.com/p/demo-release/build/demo-image/logs',
   'image built: sha256:demo-ctl-3c9e5a7b', NULL, @stopStart + INTERVAL 5 MINUTE,
   @stopStart + INTERVAL 8 MINUTE, 180000, @stopStart + INTERVAL 8 MINUTE, 0),
  (90001, 'TEST_DEPLOY', 'DEPLOY', @batch, @de, 'SUCCESS', 1, 'demo-ctl-test-deploy-1',
   'https://coding.example.com/p/demo-release/deploy/demo-test/logs', 'deployed to demo-sit',
   NULL, @stopStart + INTERVAL 8 MINUTE, @stopStart + INTERVAL 13 MINUTE, 300000,
   @stopStart + INTERVAL 13 MINUTE, 0),
  (90001, 'PROD_DEPLOY', 'DEPLOY', 1, @de, 'SUCCESS', 1, 'demo-ctl-prod-batch-1',
   'https://coding.example.com/p/demo-release/deploy/demo-prod-batch-1/logs',
   'production batch 1 online', NULL, @stopStart + INTERVAL 15 MINUTE,
   @stopStart + INTERVAL 18 MINUTE, 180000, @stopStart + INTERVAL 18 MINUTE, 0),
  (90001, 'PROD_DEPLOY', 'DEPLOY', 2, @de, 'PENDING', 0, NULL, NULL, NULL, NULL, NULL,
   NULL, NULL, @stopPaused, 0),
  (90012, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 1, 'demo-ctl-a-check-1', NULL,
   'code check passed', NULL, @archStart, @archStart + INTERVAL 2 MINUTE, 120000,
   @archStart + INTERVAL 2 MINUTE, 0),
  (90012, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-ctl-a-pkg-1', NULL,
   'package built', NULL, @archStart + INTERVAL 2 MINUTE, @archStart + INTERVAL 4 MINUTE,
   120000, @archStart + INTERVAL 4 MINUTE, 0),
  (90012, 'BUILD', 'IMG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-ctl-a-img-1', NULL,
   'image built', NULL, @archStart + INTERVAL 4 MINUTE, @archStart + INTERVAL 6 MINUTE,
   120000, @archStart + INTERVAL 6 MINUTE, 0),
  (90012, 'TEST_DEPLOY', 'DEPLOY', @batch, @de, 'PENDING', 0, NULL, NULL, NULL, NULL,
   NULL, NULL, NULL, @archStart + INTERVAL 6 MINUTE, 0),
  (90022, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 1, 'demo-ctl-b-check-1', NULL,
   'code check passed', NULL, @archStart, @archStart + INTERVAL 2 MINUTE, 120000,
   @archStart + INTERVAL 2 MINUTE, 0),
  (90022, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-ctl-b-pkg-1', NULL,
   'package built', NULL, @archStart + INTERVAL 2 MINUTE, @archStart + INTERVAL 4 MINUTE,
   120000, @archStart + INTERVAL 4 MINUTE, 0),
  (90022, 'BUILD', 'IMG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-ctl-b-img-1', NULL,
   'image built', NULL, @archStart + INTERVAL 4 MINUTE, @archStart + INTERVAL 6 MINUTE,
   120000, @archStart + INTERVAL 6 MINUTE, 0),
  (90022, 'TEST_DEPLOY', 'DEPLOY', @batch, @de, 'PENDING', 0, NULL, NULL, NULL, NULL,
   NULL, NULL, NULL, @archStart + INTERVAL 6 MINUTE, 0);

-- 10. The stop pipeline owns unit 2's lease; a stop must release it after the business commit.
INSERT INTO deploy_unit_lock (unit_id, instance_id, acquired_at, expire_at)
VALUES (2, 90001, @stopStart, UTC_TIMESTAMP(3) + INTERVAL 1 DAY)
ON DUPLICATE KEY UPDATE instance_id = VALUES(instance_id), acquired_at = VALUES(acquired_at),
                        expire_at = VALUES(expire_at);

SELECT @stopPublish AS stop_publish, @stopPipeline AS stop_pipeline,
       @archAPublish AS archive_a, @archBPublish AS archive_b,
       90001 AS archive_task_a, 90002 AS archive_task_b, @de AS demo_de;