-- Dev-only demo state for the P6-D 紧急放行 screen.
--
-- Seeds exactly one release marked by its business numbers, so the script can delete its own rows
-- and re-run:
--
--   * PO-DEMO-EMERGENCY / PL-DEMO-EMERGENCY: item 92001 is PENDING_APPROVAL, the order mirrors it,
--     and the newest snapshot needs two signatures (OPS_LEAD then DEPT_MANAGER), both PENDING. The
--     pipeline instance is PAUSED at PROD_DEPLOY (node RUNNING, batch 1 PENDING) so the release page
--     renders the production node and the 生产准入 panel where the canceled chain is visible.
--     Image digest of the instance and of the snapshot are equal, so the review preview passes.
--
-- The script is idempotent: it deletes exactly the rows of its own markers (children first) and
-- inserts them again. It also creates dev (ADMIN) and ops-lead (OPS + OPS_LEAD, never ADMIN)
-- idempotently: the probe needs a caller that must NOT see the 紧急放行 door (P6-D design section 4).
-- No existing demo order is modified; in particular PO-20261121-5976BF and the P5-C todo orders are
-- never touched.
--
-- The probe (scripts/verify-p6d-ui.mjs) performs one real emergency release and re-runs this script
-- afterwards, so later screenshots of other slices keep seeing a PENDING_APPROVAL order.
--
-- Audit and notification rows created by a real release are left as evidence.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-emergency.sql"
--
-- English-only content on purpose: this file is piped through the mysql client, which on this
-- Windows host mangles Chinese in --execute payloads.

SET @publish := 'PO-DEMO-EMERGENCY';
SET @pipeline := 'PL-DEMO-EMERGENCY';
SET @item := 92001;
SET @order := 92001;
SET @snapshot := 92001;
SET @instance := 92001;
SET @task := 92001;

-- 1. Remove only the rows this script created on a previous run (children first). The emergency
--    history row belongs to the demo order, so a re-seed also clears it; audit and notification
--    rows are never deleted.
DELETE FROM emergency_release WHERE order_id = @order OR publish_no = @publish;

DELETE FROM approval WHERE order_id = @order OR snapshot_id = @snapshot;

DELETE FROM admission_snapshot WHERE order_id = @order OR id = @snapshot;

DELETE nt FROM node_task nt
  JOIN pipeline_instance pi ON pi.id = nt.instance_id
 WHERE pi.pipeline_no = @pipeline;

DELETE pn FROM pipeline_node pn
  JOIN pipeline_instance pi ON pi.id = pn.instance_id
 WHERE pi.pipeline_no = @pipeline;

DELETE FROM deploy_unit_lock
 WHERE instance_id IN (SELECT id FROM pipeline_instance WHERE pipeline_no = @pipeline);

DELETE FROM pipeline_instance WHERE pipeline_no = @pipeline OR id = @instance;

DELETE FROM release_order WHERE publish_no = @publish OR id = @order;

DELETE FROM release_item WHERE id = @item OR crid LIKE 'CR-DEMO-EMERGENCY%';

DELETE FROM release_task_member WHERE task_id = @task;

DELETE FROM release_task WHERE id = @task OR name = 'DEMO-EMERGENCY';

-- 2. The two identities the probe switches between (idempotent; ops-lead has no ADMIN role).
INSERT IGNORE INTO app_user (id, name, status, created_at) VALUES
  ('dev', 'Dev User', 'ACTIVE', UTC_TIMESTAMP(3)),
  ('ops-lead', 'Ops Lead', 'ACTIVE', UTC_TIMESTAMP(3));

INSERT IGNORE INTO user_role (user_id, role) VALUES
  ('dev', 'ADMIN'),
  ('ops-lead', 'OPS'),
  ('ops-lead', 'OPS_LEAD');

INSERT IGNORE INTO user_scope (user_id, scope_type, scope_id) VALUES
  ('dev', 'ALL', 0),
  ('ops-lead', 'ALL', 0);

-- 3. The demo task on subsystem 2 (the same fixture the earlier seeds use).
INSERT INTO release_task
  (id, subsystem_id, name, creator, purpose, plan_finish_date, status, created_at, updated_at)
VALUES
  (@task, 2, 'DEMO-EMERGENCY', 'dev',
   'Demo task for the P6-D emergency-release probe', CURDATE() + INTERVAL 7 DAY, 'ACTIVE',
   UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, UTC_TIMESTAMP(3) - INTERVAL 1 HOUR);

-- 4. The change item: PENDING_APPROVAL with both gate dimensions passed.
INSERT INTO release_item
  (id, task_id, crid, type, unit_id, build_plan_id, source_branch, module_path, version,
   plan_release_date, status, gate_status, test_status, base_commit, last_test_pass_by,
   last_test_pass_at, test_fail_reason, row_version, created_at, updated_at)
VALUES
  (@item, @task, 'CR-DEMO-EMERGENCY', 'UNIT', 2, 'demo-build', 'release/demo-emergency', NULL,
   'V0.0.1-demo-emergency', CURDATE(), 'PENDING_APPROVAL', 'CHECK_PASS', 'TEST_PASS',
   '9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b', 'dev',
   UTC_TIMESTAMP(3) - INTERVAL 50 MINUTE, NULL, 0,
   UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, UTC_TIMESTAMP(3) - INTERVAL 40 MINUTE);

-- 5. The release order mirrors the item and starts with no emergency trace.
INSERT INTO release_order
  (id, publish_no, item_id, oa_ticket_no, planned_release_time, window_result, deadline_at,
   status, row_version, created_at, executed_by,
   emergency_release, emergency_reason, emergency_by, emergency_at)
VALUES
  (@order, @publish, @item, 'OA-DEMO-EMERGENCY', UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE, 'OUT',
   NULL, 'PENDING_APPROVAL', 0, UTC_TIMESTAMP(3) - INTERVAL 40 MINUTE, NULL,
   0, NULL, NULL, NULL);

-- 6. The admission snapshot of that submission (outside the window, so two signatures are needed).
INSERT INTO admission_snapshot
  (id, order_id, item_id, submitted_by, planned_release_time, in_window, window_id, frozen,
   freeze_ids, max_severity, check_conclusion, waiver_ids, test_conclusion, test_pass_by,
   oa_ticket_no, image_digest, chain_json, created_at)
VALUES
  (@snapshot, @order, @item, 'dev', UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE, 0, NULL, 0, '[]',
   'NONE', 'CHECK_PASS', '[]', 'TEST_PASS', 'dev', 'OA-DEMO-EMERGENCY',
   'sha256:9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b',
   '["OPS_LEAD","DEPT_MANAGER"]', UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE);

-- 7. The two pending signatures; an emergency release cancels both with the reason as comment.
INSERT INTO approval
  (order_id, snapshot_id, step_no, role, status, decided_by, decided_at, comment, created_at)
VALUES
  (@order, @snapshot, 1, 'OPS_LEAD', 'PENDING', NULL, NULL, NULL,
   UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE),
  (@order, @snapshot, 2, 'DEPT_MANAGER', 'PENDING', NULL, NULL, NULL,
   UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE);

-- 8. The pipeline instance: PAUSED at the production boundary while the approval is pending. The
--    image digest equals the snapshot's so the 执行前复核 preview reports a match.
SET @start := UTC_TIMESTAMP(3) - INTERVAL 70 MINUTE;
SET @paused := UTC_TIMESTAMP(3) - INTERVAL 20 MINUTE;

INSERT INTO pipeline_instance
  (id, order_id, pipeline_no, exec_type, trigger_type, current_node, status, status_reason,
   image_digest, commit_sha, start_at, end_at, created_at, test_ready_at, paused_at,
   item_id, unit_id, requested_by)
VALUES
  (@instance, @order, @pipeline, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'PAUSED',
   'demo: waiting for the production approval before batch 1',
   'sha256:9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b',
   '9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b', @start, NULL, @start,
   @start + INTERVAL 50 MINUTE, @paused, @item, 2, 'dev');

-- 9. Nodes: BUILD/TEST_DEPLOY/VERIFY passed; PROD_DEPLOY is RUNNING but paused at the boundary.
INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at, finished_at) VALUES
  (@instance, 'BUILD', 0, 'SUCCESS', @start, @start + INTERVAL 8 MINUTE),
  (@instance, 'TEST_DEPLOY', 1, 'SUCCESS', @start + INTERVAL 8 MINUTE,
   @start + INTERVAL 13 MINUTE),
  (@instance, 'VERIFY', 2, 'SUCCESS', @start + INTERVAL 13 MINUTE,
   @start + INTERVAL 15 MINUTE),
  (@instance, 'PROD_DEPLOY', 3, 'RUNNING', @start + INTERVAL 15 MINUTE, NULL);

-- 10. Evidence for the passed nodes and the production batch that is blocked by the approval.
SET @de := COALESCE(
  (SELECT id FROM env_de WHERE unit_id = 2 AND env_kind = 'DAILY' ORDER BY id LIMIT 1),
  (SELECT id FROM env_de WHERE unit_id = 2 AND env_kind = 'TEST' ORDER BY id LIMIT 1),
  0
);
SET @batch := COALESCE((SELECT deploy_batch_no FROM env_de WHERE id = @de), 1);

INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   external_id, external_url, result_summary, deadline_at, started_at, finished_at, duration_ms,
   updated_at, query_failures)
VALUES
  (@instance, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 1, 'demo-emergency-check-1', NULL,
   'code check passed', NULL, @start, @start + INTERVAL 2 MINUTE, 120000,
   @start + INTERVAL 2 MINUTE, 0),
  (@instance, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-emergency-pkg-1', NULL,
   'package built', NULL, @start + INTERVAL 2 MINUTE, @start + INTERVAL 5 MINUTE, 180000,
   @start + INTERVAL 5 MINUTE, 0),
  (@instance, 'BUILD', 'IMG_BUILD', 0, 0, 'SUCCESS', 1, 'demo-emergency-img-1', NULL,
   'image built: sha256:9a8b7c6d5e4f', NULL, @start + INTERVAL 5 MINUTE,
   @start + INTERVAL 8 MINUTE, 180000, @start + INTERVAL 8 MINUTE, 0),
  (@instance, 'TEST_DEPLOY', 'DEPLOY', @batch, @de, 'SUCCESS', 1,
   'demo-emergency-test-1', NULL, 'deployed to demo-sit', NULL, @start + INTERVAL 8 MINUTE,
   @start + INTERVAL 13 MINUTE, 300000, @start + INTERVAL 13 MINUTE, 0),
  (@instance, 'PROD_DEPLOY', 'DEPLOY', 1, @de, 'PENDING', 0, NULL, NULL, NULL, NULL, NULL,
   NULL, NULL, @paused, 0);

SELECT @publish AS publish_no, @pipeline AS pipeline_no, @item AS item_id,
       @order AS order_id, @snapshot AS snapshot_id, @instance AS instance_id,
       @de AS demo_de;