-- Dev-only demo state for the P6-A second-party library pages.
--
-- One LIB unit (artifact com.sf.demo / demo-sdk, backend V29 coordinates), one same-system UNIT
-- whose module_path equals the artifactId (so the documented dependents heuristic has a hit), one
-- LIB task / demo item / release order, and two executions of that order:
--
--   PL-DEMO-LIB-FAIL  BUILD failed with ENGINE_UNAVAILABLE (no CODING connection), the later two
--                     nodes stayed PENDING;
--   PL-DEMO-LIB-DONE  the full BUILD -> LIB_SECURITY -> LIB_PUBLISH pipeline, three checks in
--                     gate_check (one WAIVED under an approved waiver), artifact uploaded.
--
-- The newest instance (DONE) is what /releases/PO-DEMO-LIB shows; the FAILED one is the second row
-- of its 发布记录 tab. A second LIB item CR-DEMO-LIB-LIVE stays PENDING so the real-API publish
-- evidence can be taken against it; re-running this script removes that evidence (the release
-- order/instance/lock/audit it created) and restores the state below.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-lib.sql"
--
-- Idempotent: every row is addressed by the fixed demo ids below or by the two demo CRIDs; the
-- script deletes those rows (and any API-created order of the two items) before inserting them
-- again. Existing demo units/items and their orders are never touched.

SET @libUnit := 91001;
SET @depUnit := 91002;
SET @task := 91001;
SET @demoItem := 91001;
SET @liveItem := 91002;
SET @order := 91001;
SET @publishNo := 'PO-DEMO-LIB';
SET @doneNo := 'PL-DEMO-LIB-DONE';
SET @failNo := 'PL-DEMO-LIB-FAIL';
SET @now := UTC_TIMESTAMP(3);

-- 1. Remove previous demo rows and any publish evidence of CR-DEMO-LIB-LIVE (children first).
DELETE nt FROM node_task nt
  JOIN pipeline_instance pi ON pi.id = nt.instance_id
  LEFT JOIN release_order o ON o.id = pi.order_id
 WHERE pi.pipeline_no IN (@doneNo, @failNo)
    OR pi.item_id IN (@demoItem, @liveItem)
    OR o.item_id IN (@demoItem, @liveItem);

DELETE pn FROM pipeline_node pn
  JOIN pipeline_instance pi ON pi.id = pn.instance_id
  LEFT JOIN release_order o ON o.id = pi.order_id
 WHERE pi.pipeline_no IN (@doneNo, @failNo)
    OR pi.item_id IN (@demoItem, @liveItem)
    OR o.item_id IN (@demoItem, @liveItem);

DELETE FROM pipeline_instance
 WHERE pipeline_no IN (@doneNo, @failNo)
    OR item_id IN (@demoItem, @liveItem)
    OR order_id = @order
    OR order_id IN (SELECT id FROM release_order WHERE item_id IN (@demoItem, @liveItem));

DELETE FROM release_order WHERE item_id IN (@demoItem, @liveItem) OR publish_no = @publishNo;
DELETE FROM gate_check WHERE item_id IN (@demoItem, @liveItem);
DELETE FROM gate_waiver WHERE item_id IN (@demoItem, @liveItem);
DELETE FROM deploy_unit_lock WHERE unit_id = @libUnit;
-- The seed inserts one demo PUBLISH row below; drop what a previous run or the live publish wrote.
DELETE FROM audit_log WHERE scope_unit_id = @libUnit;
DELETE FROM release_item WHERE id IN (@demoItem, @liveItem);
DELETE FROM release_task WHERE id = @task;
DELETE FROM coding_task_binding WHERE unit_id IN (@libUnit, @depUnit);
DELETE FROM deploy_unit WHERE id IN (@libUnit, @depUnit);

-- 2. The library, its dependent deployment unit, and the two CODING bindings a LIB publish needs.
INSERT INTO deploy_unit
  (id, subsystem_id, name, type, du, owner_id, framework_template, branch_mode, status,
   description, module_path, deploy_type, image_type, image_name, module, tag,
   artifact_group_id, artifact_artifact_id, created_at, updated_at)
VALUES
  (@libUnit, 2, 'demo-sdk', 'LIB', NULL, 'dev', NULL, 'STANDARD', 'ACTIVE',
   '二方库演示单元：制品 com.sf.demo:demo-sdk，无部署与环境配置', NULL, NULL, NULL, NULL, NULL, NULL,
   'com.sf.demo', 'demo-sdk', @now - INTERVAL 2 DAY, @now - INTERVAL 1 HOUR),
  (@depUnit, 2, 'demo-sdk-consumer', 'UNIT', 'DU-DEMO-SDK', 'dev', 'spring-boot', 'STANDARD', 'ACTIVE',
   '依赖 demo-sdk 的演示部署单元（module_path = demo-sdk）', 'demo-sdk', 'K8S', 'IMAGE',
   'demo-sdk-consumer:latest', NULL, NULL, NULL, NULL, @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY);

INSERT INTO coding_task_binding
  (unit_id, task_kind, task_id, params_json, enabled, created_at, updated_at)
VALUES
  (@libUnit, 'BUILD', 'demo-lib-build', CAST('{"branch":"release/demo-lib"}' AS JSON), 1,
   @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY),
  (@libUnit, 'LIB_PUBLISH', 'demo-lib-publish', NULL, 1,
   @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY);

-- 3. One LIB task, the demo item and the PENDING item used for the real-API publish evidence.
INSERT INTO release_task
  (id, subsystem_id, name, creator, purpose, plan_finish_date, status, created_at, updated_at)
VALUES
  (@task, 2, 'DEMO-LIB', 'dev', '二方库制品发布演示', DATE_ADD(CURDATE(), INTERVAL 7 DAY), 'ACTIVE',
   @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY);

INSERT INTO release_item
  (id, task_id, crid, type, unit_id, build_plan_id, source_branch, module_path, version,
   plan_release_date, status, gate_status, test_status, base_commit, last_test_pass_by,
   last_test_pass_at, test_fail_reason, row_version, created_at, updated_at)
VALUES
  (@demoItem, @task, 'CR-DEMO-LIB-RELEASE', 'LIB', @libUnit, 'demo-lib-build',
   'release/demo-lib', NULL, 'V1.0.0-demo', DATE_ADD(CURDATE(), INTERVAL 3 DAY),
   'DONE', 'CHECK_PASS', 'TEST_PENDING', NULL, NULL, NULL, NULL, 0,
   @now - INTERVAL 4 HOUR, @now - INTERVAL 3 HOUR),
  (@liveItem, @task, 'CR-DEMO-LIB-LIVE', 'LIB', @libUnit, 'demo-lib-build',
   'release/demo-lib-live', NULL, 'V1.0.1-demo', DATE_ADD(CURDATE(), INTERVAL 5 DAY),
   'PENDING', 'CHECK_RUNNING', 'TEST_PENDING', NULL, NULL, NULL, NULL, 0,
   @now - INTERVAL 30 MINUTE, @now - INTERVAL 30 MINUTE);

INSERT INTO release_order
  (id, publish_no, item_id, oa_ticket_no, planned_release_time, window_result, deadline_at,
   status, row_version, created_at, executed_by)
VALUES
  (@order, @publishNo, @demoItem, NULL, @now + INTERVAL 1 DAY, NULL, NULL,
   'DONE', 0, @now - INTERVAL 4 HOUR, 'dev');

-- 4. Two executions: FAILED first (older id), DONE second (the newest instance the page shows).
INSERT INTO pipeline_instance
  (order_id, pipeline_no, exec_type, trigger_type, current_node, status, status_reason,
   image_digest, commit_sha, start_at, end_at, created_at, test_ready_at,
   item_id, unit_id, requested_by)
VALUES
  (@order, @failNo, 'LIB', 'INITIAL', 'BUILD', 'FAILED',
   'BUILD 失败：ENGINE_UNAVAILABLE（未配置 CODING 连接），安全检测与入库未开始',
   NULL, NULL, @now - INTERVAL 20 MINUTE, @now - INTERVAL 18 MINUTE,
   @now - INTERVAL 20 MINUTE, NULL, @demoItem, @libUnit, 'qa-zhang'),
  (@order, @doneNo, 'LIB', 'INITIAL', 'LIB_PUBLISH', 'DONE', NULL,
   NULL, '3c9e5a7b1d4f2a6c8e0b3d5f7a9c1e4b6d8f0a2c',
   @now - INTERVAL 15 MINUTE, @now - INTERVAL 3 MINUTE,
   @now - INTERVAL 15 MINUTE, NULL, @demoItem, @libUnit, 'dev');

SET @failInst := (SELECT id FROM pipeline_instance WHERE pipeline_no = @failNo);
SET @doneInst := (SELECT id FROM pipeline_instance WHERE pipeline_no = @doneNo);

INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at, finished_at)
VALUES
  (@failInst, 'BUILD', 0, 'FAILED', @now - INTERVAL 20 MINUTE, @now - INTERVAL 18 MINUTE),
  (@failInst, 'LIB_SECURITY', 1, 'PENDING', NULL, NULL),
  (@failInst, 'LIB_PUBLISH', 2, 'PENDING', NULL, NULL),
  (@doneInst, 'BUILD', 0, 'SUCCESS', @now - INTERVAL 15 MINUTE, @now - INTERVAL 11 MINUTE),
  (@doneInst, 'LIB_SECURITY', 1, 'SUCCESS', @now - INTERVAL 11 MINUTE, @now - INTERVAL 7 MINUTE),
  (@doneInst, 'LIB_PUBLISH', 2, 'SUCCESS', @now - INTERVAL 7 MINUTE, @now - INTERVAL 3 MINUTE);

-- 5. Tasks: BUILD is only CODE_CHECK -> PKG_BUILD (no IMG_BUILD for a library);
--    LIB_SECURITY has no node_task rows (its checks are gate rows); LIB_PUBLISH is one upload task.
INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   external_id, external_url, result_summary, started_at, finished_at, duration_ms, updated_at)
VALUES
  (@failInst, 'BUILD', 'CODE_CHECK', 0, 0, 'FAILED', 1,
   NULL, NULL, 'ENGINE_UNAVAILABLE: CODING connection is not configured (build.start)',
   @now - INTERVAL 20 MINUTE, @now - INTERVAL 18 MINUTE, 120000, @now - INTERVAL 18 MINUTE),
  (@failInst, 'BUILD', 'PKG_BUILD', 0, 0, 'SKIPPED', 0,
   NULL, NULL, NULL, NULL, NULL, NULL, @now - INTERVAL 18 MINUTE),
  (@failInst, 'LIB_PUBLISH', 'LIB_PUBLISH', 0, 0, 'PENDING', 0,
   NULL, NULL, NULL, NULL, NULL, NULL, @now - INTERVAL 18 MINUTE),

  (@doneInst, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 1,
   'demo-lib-check-1', 'https://coding.example.com/p/demo-release/build/demo-lib-check/logs',
   'code check passed', @now - INTERVAL 15 MINUTE, @now - INTERVAL 14 MINUTE, 60000,
   @now - INTERVAL 14 MINUTE),
  (@doneInst, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 1,
   'demo-lib-pkg-1', 'https://coding.example.com/p/demo-release/build/demo-lib-pkg/logs',
   'artifact packaged: com.sf.demo:demo-sdk:V1.0.0-demo',
   @now - INTERVAL 14 MINUTE, @now - INTERVAL 11 MINUTE, 180000, @now - INTERVAL 11 MINUTE),
  (@doneInst, 'LIB_PUBLISH', 'LIB_PUBLISH', 0, 0, 'SUCCESS', 1,
   'demo-lib-publish-1', 'https://coding.example.com/p/demo-release/publish/demo-lib/logs',
   'artifact uploaded: com.sf.demo:demo-sdk:V1.0.0-demo',
   @now - INTERVAL 7 MINUTE, @now - INTERVAL 3 MINUTE, 240000, @now - INTERVAL 3 MINUTE);

-- 6. The three artifact checks of the DONE execution, one waived (the page marks 豁免入库).
INSERT INTO gate_check
  (item_id, order_id, instance_id, check_kind, stage, status, severity, issues,
   scanned_object, external_id, report_url, summary, attempt, started_at, finished_at,
   duration_ms, deadline_at, created_at, updated_at)
VALUES
  (@demoItem, @order, @doneInst, 'SONAR', 'IMAGE', 'PASS', 'S3', 2,
   'com.sf.demo:demo-sdk:V1.0.0-demo', 'sonar-lib-1',
   'https://sonar.example.com/dashboard?id=demo-sdk',
   '2 处低危坏味道，不阻断入库', 1,
   @now - INTERVAL 11 MINUTE, @now - INTERVAL 9 MINUTE, 120000, NULL, @now, @now),
  (@demoItem, @order, @doneInst, 'COMPONENT_VULN', 'IMAGE', 'FAIL', 'S2', 3,
   'com.sf.demo:demo-sdk:V1.0.0-demo', 'sca-lib-1',
   'https://sca.example.com/report/demo-sdk',
   '3 个中危组件漏洞，已批准豁免入库', 1,
   @now - INTERVAL 10 MINUTE, @now - INTERVAL 8 MINUTE, 120000, NULL, @now, @now),
  (@demoItem, @order, @doneInst, 'VERSION_DEFECT', 'IMAGE', 'PASS', 'NONE', 0,
   'com.sf.demo:demo-sdk:V1.0.0-demo', 'defect-lib-1', NULL,
   '版本未命中已知缺陷', 1,
   @now - INTERVAL 9 MINUTE, @now - INTERVAL 7 MINUTE, 90000, NULL, @now, @now);

INSERT INTO gate_waiver
  (item_id, order_id, status, reason, impact, requested_by, requested_at, approved_by,
   approved_at, decide_reason, expire_at, commit_sha, image_digest, created_at, updated_at)
VALUES
  (@demoItem, @order, 'APPROVED', '3 个中危组件漏洞本次窗口内无法修复，申请豁免入库',
   '仅演示数据；允许该版本制品入库，到期不自动下架', 'qa-zhang', @now - INTERVAL 8 MINUTE,
   'dev', @now - INTERVAL 6 MINUTE, '同意，限本次制品版本', @now + INTERVAL 14 DAY,
   '3c9e5a7b1d4f2a6c8e0b3d5f7a9c1e4b6d8f0a2c', NULL,
   @now - INTERVAL 8 MINUTE, @now - INTERVAL 6 MINUTE);

-- 7. One PUBLISH audit row so the unit-detail 操作日志 block has a LIB example.
INSERT INTO audit_log
  (actor, action, object_type, object_id, object_label, summary, reason, before_json,
   after_json, scope_unit_id, trace_id, created_at)
VALUES
  ('dev', 'PUBLISH', 'ORDER', @publishNo, @publishNo,
   '发起二方库发布：发布单 PO-DEMO-LIB（变更项 CR-DEMO-LIB-RELEASE）', NULL,
   JSON_OBJECT('itemStatus', 'PENDING'),
   JSON_OBJECT('itemStatus', 'BUILDING', 'publishNo', @publishNo,
              'pipelineNo', @doneNo, 'type', 'LIB'),
   @libUnit, NULL, @now - INTERVAL 15 MINUTE);

SELECT @libUnit AS lib_unit, @depUnit AS dependent_unit, @demoItem AS demo_item,
       @liveItem AS live_item, @publishNo AS publish_no,
       @doneNo AS done_pipeline, @failNo AS failed_pipeline;