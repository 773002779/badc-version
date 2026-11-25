-- Dev-only demo state for the P5-C 待办中心 (/todos).
--
-- Seeds one deterministic row per queue, all marked by their own business numbers / names so the
-- script can delete exactly its own rows and re-run:
--
--   * test      task 90003 DEMO-TODO-TEST: a TESTING item whose newest instance has test_ready_at
--               three hours ago and whose task has `dev` as a TEST member. The instance is PAUSED
--               on purpose: the reconcile job never advances a PAUSED instance, so the demo row
--               keeps its exact waiting time.
--   * execute   order PO-DEMO-TODO-EXECUTE (READY, created two hours ago) on task 90004.
--   * approval  two PENDING_APPROVAL orders whose snapshots were submitted by `dev`:
--                 A: step 1 OPS_LEAD + step 2 DEPT_MANAGER, waiting five hours -> ops-lead
--                 B: step 1 DEPT_MANAGER, waiting two hours                -> dept-manager
--               (the design only shows the *lowest* pending step, which is what the probe checks:
--               dept-manager must not see order A before ops-lead signs it.)
--   * mine      tasks 90005 DEMO-TODO-MINE (updated two hours ago) and 90007 DEMO-TODO-NOW
--               (updated now -> 刚刚), both created by `dev` with one PENDING item.
--
-- It also creates the three demo identities idempotently (dev / ops-lead / dept-manager, roles and
-- ALL data scope) plus `demo-bot`, an ACTIVE user with no roles and no scope. demo-bot owns the
-- test/execute/approval demo tasks, so those rows never pollute a real identity's `mine` queue, and
-- it doubles as the fail-closed "all four groups empty" identity of the UI probe.
--
-- The tasks live on subsystem 2 / unit 2, the same demo fixtures the earlier P5-A/P5-B seeds use.
-- Re-run this script immediately before scripts/verify-p5c-ui.mjs: the "刚刚" row is relative to
-- the seed time.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-todos.sql"
--
-- English-only content on purpose: this file is piped through the mysql client, which on this
-- Windows host mangles Chinese in --execute payloads.

SET @testPublish := 'PO-DEMO-TODO-TEST';
SET @execPublish := 'PO-DEMO-TODO-EXECUTE';
SET @apprAPublish := 'PO-DEMO-TODO-APPROVAL-A';
SET @apprBPublish := 'PO-DEMO-TODO-APPROVAL-B';
SET @testPipeline := 'PL-DEMO-TODO-TEST';

-- 1. Remove only the rows this script created on a previous run (children first).
DELETE FROM approval
 WHERE order_id IN (90034, 90035) OR snapshot_id IN (90041, 90042);

DELETE FROM admission_snapshot
 WHERE order_id IN (90034, 90035) OR id IN (90041, 90042);

DELETE nt FROM node_task nt
  JOIN pipeline_instance pi ON pi.id = nt.instance_id
 WHERE pi.pipeline_no = @testPipeline;

DELETE pn FROM pipeline_node pn
  JOIN pipeline_instance pi ON pi.id = pn.instance_id
 WHERE pi.pipeline_no = @testPipeline;

DELETE FROM pipeline_instance WHERE pipeline_no = @testPipeline;

DELETE FROM release_order
 WHERE publish_no IN (@testPublish, @execPublish, @apprAPublish, @apprBPublish)
    OR id IN (90031, 90032, 90034, 90035);

DELETE FROM release_item
 WHERE id IN (90031, 90032, 90033, 90034, 90035, 90036)
    OR crid LIKE 'CR-DEMO-TODO-%';

DELETE FROM release_task_member
 WHERE task_id IN (90003, 90004, 90005, 90006, 90007);

DELETE FROM release_task
 WHERE id IN (90003, 90004, 90005, 90006, 90007) OR name LIKE 'DEMO-TODO-%';

-- 2. The identities the probe switches between (idempotent; names in English avoid the mysql
--    client's charset handling). demo-bot gets no role and no scope on purpose.
INSERT IGNORE INTO app_user (id, name, status, created_at) VALUES
  ('dev', 'Dev User', 'ACTIVE', UTC_TIMESTAMP(3)),
  ('ops-lead', 'Ops Lead', 'ACTIVE', UTC_TIMESTAMP(3)),
  ('dept-manager', 'Dept Manager', 'ACTIVE', UTC_TIMESTAMP(3)),
  ('demo-bot', 'Demo Bot (no roles)', 'ACTIVE', UTC_TIMESTAMP(3));

INSERT IGNORE INTO user_role (user_id, role) VALUES
  ('dev', 'ADMIN'),
  ('ops-lead', 'OPS'),
  ('ops-lead', 'OPS_LEAD'),
  ('dept-manager', 'DEPT_MANAGER');

INSERT IGNORE INTO user_scope (user_id, scope_type, scope_id) VALUES
  ('dev', 'ALL', 0),
  ('ops-lead', 'ALL', 0),
  ('dept-manager', 'ALL', 0);

-- 3. Tasks. The test/execute/approval tasks belong to demo-bot; the two `mine` demo tasks belong to
--    dev. updated_at drives the displayed waiting time of the `mine` rows.
INSERT INTO release_task
  (id, subsystem_id, name, creator, purpose, plan_finish_date, status, created_at, updated_at)
VALUES
  (90003, 2, 'DEMO-TODO-TEST', 'demo-bot',
   'Demo task for the P5-C 待测试 queue', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3) - INTERVAL 4 HOUR, UTC_TIMESTAMP(3) - INTERVAL 3 HOUR),
  (90004, 2, 'DEMO-TODO-EXECUTE', 'demo-bot',
   'Demo task for the P5-C 待执行 queue', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, UTC_TIMESTAMP(3) - INTERVAL 2 HOUR),
  (90005, 2, 'DEMO-TODO-MINE', 'dev',
   'Demo task for the P5-C 我发起的 queue (two hours)', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, UTC_TIMESTAMP(3) - INTERVAL 2 HOUR),
  (90006, 2, 'DEMO-TODO-APPROVAL', 'demo-bot',
   'Demo task for the P5-C 待审批 queue', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3) - INTERVAL 6 HOUR, UTC_TIMESTAMP(3) - INTERVAL 5 HOUR),
  (90007, 2, 'DEMO-TODO-NOW', 'dev',
   'Demo task for the P5-C 我发起的 queue (just now)', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 4. Members: dev is the TEST member the 待测试 queue joins on; ops-lead is the OPS member of the
--    execute demo task (the execute permission alone also matches, this keeps the row honest).
INSERT INTO release_task_member (task_id, user_id, role, created_at, updated_at) VALUES
  (90003, 'dev', 'TEST', UTC_TIMESTAMP(3) - INTERVAL 4 HOUR, UTC_TIMESTAMP(3) - INTERVAL 4 HOUR),
  (90003, 'ops-lead', 'OPS', UTC_TIMESTAMP(3) - INTERVAL 4 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 4 HOUR),
  (90004, 'ops-lead', 'OPS', UTC_TIMESTAMP(3) - INTERVAL 3 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 3 HOUR),
  (90005, 'dev', 'DEV', UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, UTC_TIMESTAMP(3) - INTERVAL 3 HOUR),
  (90007, 'dev', 'DEV', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 5. Change items. Unit 2 is the same demo unit as the earlier seeds; the test item is TESTING
--    with a not-yet-passed test dimension, the approval items are PENDING_APPROVAL.
INSERT INTO release_item
  (id, task_id, crid, type, unit_id, build_plan_id, source_branch, module_path, version,
   plan_release_date, status, gate_status, test_status, base_commit, last_test_pass_by,
   last_test_pass_at, test_fail_reason, row_version, created_at, updated_at)
VALUES
  (90031, 90003, 'CR-DEMO-TODO-TEST', 'UNIT', 2, 'demo-build', 'release/demo-todo-test', NULL,
   'V0.0.1-demo-todo', CURDATE(), 'TESTING', 'CHECK_PASS', 'TEST_PENDING', NULL, NULL, NULL, NULL,
   0, UTC_TIMESTAMP(3) - INTERVAL 4 HOUR, UTC_TIMESTAMP(3) - INTERVAL 3 HOUR),
  (90032, 90004, 'CR-DEMO-TODO-EXECUTE', 'UNIT', 2, 'demo-build', 'release/demo-todo-exec', NULL,
   'V0.0.2-demo-todo', CURDATE(), 'READY', 'CHECK_PASS', 'TEST_PASS', '0f0e0d0c0b0a09080706050403020100f0e0d0c0',
   'dev', UTC_TIMESTAMP(3) - INTERVAL 4 HOUR, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, UTC_TIMESTAMP(3) - INTERVAL 2 HOUR),
  (90033, 90005, 'CR-DEMO-TODO-MINE', 'UNIT', 2, 'demo-build', 'release/demo-todo-mine', NULL,
   'V0.0.3-demo-todo', CURDATE(), 'PENDING', 'CHECK_PASS', 'TEST_PENDING', NULL, NULL, NULL, NULL,
   0, UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, UTC_TIMESTAMP(3) - INTERVAL 2 HOUR),
  (90034, 90006, 'CR-DEMO-TODO-APPROVAL-A', 'UNIT', 2, 'demo-build', 'release/demo-todo-appr-a',
   NULL, 'V0.0.4-demo-todo', CURDATE(), 'PENDING_APPROVAL', 'CHECK_PASS', 'TEST_PASS',
   '1a1b1c1d1e1f10111213141516171819a1b1c1d1', 'dev', UTC_TIMESTAMP(3) - INTERVAL 6 HOUR, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 6 HOUR, UTC_TIMESTAMP(3) - INTERVAL 5 HOUR),
  (90035, 90006, 'CR-DEMO-TODO-APPROVAL-B', 'UNIT', 2, 'demo-build', 'release/demo-todo-appr-b',
   NULL, 'V0.0.5-demo-todo', CURDATE(), 'PENDING_APPROVAL', 'CHECK_PASS', 'TEST_PASS',
   '2a2b2c2d2e2f20212223242526272829a2b2c2d2', 'dev', UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, UTC_TIMESTAMP(3) - INTERVAL 2 HOUR),
  (90036, 90007, 'CR-DEMO-TODO-NOW', 'UNIT', 2, 'demo-build', 'release/demo-todo-now', NULL,
   'V0.0.6-demo-todo', CURDATE(), 'PENDING', 'CHECK_PASS', 'TEST_PENDING', NULL, NULL, NULL, NULL,
   0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 6. Release orders. The execute order is READY; the two approval orders are PENDING_APPROVAL.
--    `waitingSince` of 待执行 is order.created_at (the table has no updated_at), hence two hours.
INSERT INTO release_order
  (id, publish_no, item_id, oa_ticket_no, planned_release_time, window_result, deadline_at,
   status, row_version, created_at, executed_by)
VALUES
  (90031, @testPublish, 90031, NULL, NULL, NULL, NULL, 'TESTING', 0,
   UTC_TIMESTAMP(3) - INTERVAL 4 HOUR, NULL),
  (90032, @execPublish, 90032, 'OA-DEMO-TODO-EXEC', UTC_TIMESTAMP(3) + INTERVAL 1 DAY, 'IN', NULL,
   'READY', 0, UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, NULL),
  (90034, @apprAPublish, 90034, 'OA-DEMO-TODO-APPR-A', UTC_TIMESTAMP(3) - INTERVAL 6 HOUR, 'OUT',
   NULL, 'PENDING_APPROVAL', 0, UTC_TIMESTAMP(3) - INTERVAL 6 HOUR, NULL),
  (90035, @apprBPublish, 90035, 'OA-DEMO-TODO-APPR-B', UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, 'IN',
   NULL, 'PENDING_APPROVAL', 0, UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, NULL);

-- 7. The test instance: test_ready_at three hours ago, PAUSED so the reconcile job leaves it alone.
INSERT INTO pipeline_instance
  (id, order_id, pipeline_no, exec_type, trigger_type, current_node, status, status_reason,
   image_digest, commit_sha, start_at, end_at, created_at, test_ready_at, paused_at,
   item_id, unit_id, requested_by)
VALUES
  (90031, 90031, @testPipeline, 'ONLINE', 'INITIAL', 'TEST_DEPLOY', 'PAUSED',
   'demo: test environment ready, waiting for QA', NULL,
   '3c9e5a7b1d4f2a6c8e0b3d5f7a9c1e4b6d8f0a2c', UTC_TIMESTAMP(3) - INTERVAL 4 HOUR, NULL,
   UTC_TIMESTAMP(3) - INTERVAL 4 HOUR, UTC_TIMESTAMP(3) - INTERVAL 3 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, 90031, 2, 'demo-bot');

INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at, finished_at) VALUES
  (90031, 'BUILD', 0, 'SUCCESS', UTC_TIMESTAMP(3) - INTERVAL 4 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 3 HOUR),
  (90031, 'TEST_DEPLOY', 1, 'SUCCESS', UTC_TIMESTAMP(3) - INTERVAL 3 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 3 HOUR),
  (90031, 'VERIFY', 2, 'PENDING', NULL, NULL),
  (90031, 'PROD_DEPLOY', 3, 'PENDING', NULL, NULL);

-- 8. Approval evidence. A was submitted outside the window during a (demo) freeze, so it needs the
--    two signatures; B is inside the window and needs DEPT_MANAGER. Both pass the C20 self-approval
--    rule for ops-lead / dept-manager because the submitter is dev.
INSERT INTO admission_snapshot
  (id, order_id, item_id, submitted_by, planned_release_time, in_window, window_id, frozen,
   freeze_ids, max_severity, check_conclusion, waiver_ids, test_conclusion, test_pass_by,
   oa_ticket_no, image_digest, chain_json, created_at)
VALUES
  (90041, 90034, 90034, 'dev', UTC_TIMESTAMP(3) - INTERVAL 6 HOUR, 0, NULL, 1, '[]', 'NONE',
   'CHECK_PASS', '[]', 'TEST_PASS', 'dev', 'OA-DEMO-TODO-APPR-A', 'sha256:demo-todo-appr-a',
   '["OPS_LEAD","DEPT_MANAGER"]', UTC_TIMESTAMP(3) - INTERVAL 5 HOUR),
  (90042, 90035, 90035, 'dev', UTC_TIMESTAMP(3) - INTERVAL 3 HOUR, 1, NULL, 0, '[]', 'NONE',
   'CHECK_PASS', '[]', 'TEST_PASS', 'dev', 'OA-DEMO-TODO-APPR-B', 'sha256:demo-todo-appr-b',
   '["DEPT_MANAGER"]', UTC_TIMESTAMP(3) - INTERVAL 2 HOUR);

INSERT INTO approval
  (order_id, snapshot_id, step_no, role, status, decided_by, decided_at, comment, created_at)
VALUES
  (90034, 90041, 1, 'OPS_LEAD', 'PENDING', NULL, NULL, NULL,
   UTC_TIMESTAMP(3) - INTERVAL 5 HOUR),
  (90034, 90041, 2, 'DEPT_MANAGER', 'PENDING', NULL, NULL, NULL,
   UTC_TIMESTAMP(3) - INTERVAL 5 HOUR),
  (90035, 90042, 1, 'DEPT_MANAGER', 'PENDING', NULL, NULL, NULL,
   UTC_TIMESTAMP(3) - INTERVAL 2 HOUR);

SELECT @testPublish AS test_publish, @execPublish AS exec_publish,
       @apprAPublish AS approval_a, @apprBPublish AS approval_b,
       @testPipeline AS test_pipeline, 'demo-bot' AS empty_identity;