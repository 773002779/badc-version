-- Demo data for the P2 verification panel. LOCAL ONLY.
--
-- The phase-1 environment has no CODING tenant, so a real publish fails at BUILD with
-- ENGINE_UNAVAILABLE. To look at (and screenshot) the gate the way it behaves once the engine
-- answers, this script rewinds one existing execution to "the checks have just reported" and fills
-- the check rows.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-gate.sql"
--
-- Switch the scenario between 'pass' (everything green, admission satisfied) and 'waiver'
-- (one 中危 finding still blocking, so the page offers the waiver flow).
--
-- Chinese text is written as UTF-8 straight into the summaries on purpose: this is demo content,
-- and the console/client charset must be utf8mb4 when running it.

SET @scenario := 'pass';
SET @item := 2;
SET @instance := 2;
SET @de := 'demo-api-sit';

-- 1. Rewind the execution to "test deployed, checks reported".
UPDATE pipeline_instance
   SET status = CASE WHEN @scenario = 'waiver' THEN 'RUNNING' ELSE 'PAUSED' END,
       current_node = CASE WHEN @scenario = 'waiver' THEN 'VERIFY' ELSE 'PROD_DEPLOY' END,
       status_reason = CASE WHEN @scenario = 'waiver' THEN NULL
                            ELSE '节点 PROD_DEPLOY 没有注册 NodeHandler，流水线停在 PROD_DEPLOY 之前（PAUSED）' END,
       end_at = CASE WHEN @scenario = 'waiver' THEN NULL ELSE UTC_TIMESTAMP(3) - INTERVAL 30 MINUTE END,
       image_digest = 'sha256:6f2c1d9b7a4e83c05d1f2b6a9c8e7d4b3a2f1e0c9b8a7f6e5d4c3b2a1f0e9d8c',
       commit_sha = '3d1f7a9c2b4e8f6a0c5d7e9b1f3a5c7e9d2b4f6a',
       test_ready_at = UTC_TIMESTAMP(3) - INTERVAL 42 MINUTE
 WHERE id = @instance;

UPDATE pipeline_node
   SET status = 'SUCCESS', started_at = UTC_TIMESTAMP(3) - INTERVAL 55 MINUTE,
       finished_at = UTC_TIMESTAMP(3) - INTERVAL 44 MINUTE
 WHERE instance_id = @instance AND node_type IN ('BUILD', 'TEST_DEPLOY');

UPDATE pipeline_node
   SET status = CASE WHEN @scenario = 'waiver' THEN 'RUNNING' ELSE 'SUCCESS' END,
       started_at = UTC_TIMESTAMP(3) - INTERVAL 42 MINUTE,
       finished_at = CASE WHEN @scenario = 'waiver' THEN NULL
                          ELSE UTC_TIMESTAMP(3) - INTERVAL 31 MINUTE END
 WHERE instance_id = @instance AND node_type = 'VERIFY';

UPDATE pipeline_node
   SET status = 'PENDING', started_at = NULL, finished_at = NULL
 WHERE instance_id = @instance AND node_type = 'PROD_DEPLOY';

UPDATE node_task SET status = 'SUCCESS', finished_at = UTC_TIMESTAMP(3) - INTERVAL 44 MINUTE
 WHERE instance_id = @instance AND node_type IN ('BUILD', 'TEST_DEPLOY');

-- 2. Both gates of the item.
UPDATE release_item
   SET status = CASE WHEN @scenario = 'waiver' THEN 'TESTING' ELSE 'GATE_PENDING' END,
       gate_status = CASE WHEN @scenario = 'waiver' THEN 'CHECK_FAIL' ELSE 'CHECK_PASS' END,
       test_status = 'TEST_PASS',
       last_test_pass_by = '01388007',
       last_test_pass_at = UTC_TIMESTAMP(3) - INTERVAL 38 MINUTE,
       test_fail_reason = NULL
 WHERE id = @item;

-- 3. The seven checks. Image-level results are older than the deployment (they ran after the build),
--    the environment-level ones are younger: exactly the §4.3 timing.
DELETE FROM gate_check WHERE item_id = @item;

INSERT INTO gate_check (item_id, order_id, instance_id, check_kind, stage, status, severity,
                        issues, scanned_object, external_id, report_url, summary, attempt,
                        started_at, finished_at, duration_ms, deadline_at, created_at, updated_at)
VALUES
  (@item, 2, @instance, 'SONAR', 'IMAGE', 'PASS', 'S3', 2,
   'sha256:6f2c1d9b7a4e83c05d1f2b6a9c8e7d4b3a2f1e0c9b8a7f6e5d4c3b2a1f0e9d8c',
   'sonar-88231', 'https://sonar.example.com/dashboard?id=release-pipeline',
   '2 处低危坏味道，不阻断', 1,
   UTC_TIMESTAMP(3) - INTERVAL 50 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 46 MINUTE, 240000,
   NULL, NOW(3), NOW(3)),
  (@item, 2, @instance, 'COMPONENT_VULN', 'IMAGE', 'PASS', 'NONE', 0,
   'sha256:6f2c1d9b7a4e83c05d1f2b6a9c8e7d4b3a2f1e0c9b8a7f6e5d4c3b2a1f0e9d8c',
   'sca-5510', 'https://sca.example.com/report/5510', '未发现已知漏洞', 1,
   UTC_TIMESTAMP(3) - INTERVAL 49 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 47 MINUTE, 120000,
   NULL, NOW(3), NOW(3)),
  (@item, 2, @instance, 'ARCHITECTURE', 'IMAGE', 'PASS', 'NONE', 0,
   'sha256:6f2c1d9b7a4e83c05d1f2b6a9c8e7d4b3a2f1e0c9b8a7f6e5d4c3b2a1f0e9d8c',
   'arch-2207', NULL, '分层与依赖符合规范', 1,
   UTC_TIMESTAMP(3) - INTERVAL 48 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 47 MINUTE, 60000,
   NULL, NOW(3), NOW(3)),
  (@item, 2, @instance, 'VERSION_DEFECT', 'IMAGE', 'PASS', 'NONE', 0,
   'sha256:6f2c1d9b7a4e83c05d1f2b6a9c8e7d4b3a2f1e0c9b8a7f6e5d4c3b2a1f0e9d8c',
   'defect-1190', NULL, '版本未命中已知缺陷', 1,
   UTC_TIMESTAMP(3) - INTERVAL 48 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 46 MINUTE, 45000,
   NULL, NOW(3), NOW(3)),
  (@item, 2, @instance, 'SECURITY_DYNAMIC', 'ENV', 'PASS', 'NONE', 0, @de,
   'dast-7781', 'https://dast.example.com/scan/7781', '未发现高危漏洞', 1,
   UTC_TIMESTAMP(3) - INTERVAL 40 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 33 MINUTE, 420000,
   NULL, NOW(3), NOW(3)),
  (@item, 2, @instance, 'AUTO_TEST', 'ENV',
   CASE WHEN @scenario = 'waiver' THEN 'FAIL' ELSE 'PASS' END,
   CASE WHEN @scenario = 'waiver' THEN 'S2' ELSE 'NONE' END,
   CASE WHEN @scenario = 'waiver' THEN 5 ELSE 0 END, @de,
   'autotest-9042', 'https://autotest.example.com/run/9042',
   CASE WHEN @scenario = 'waiver' THEN '5 个用例失败：报表导出超时' ELSE '全部 128 个用例通过' END,
   1, UTC_TIMESTAMP(3) - INTERVAL 40 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 31 MINUTE, 540000,
   NULL, NOW(3), NOW(3)),
  (@item, 2, @instance, 'DB_NORM', 'ENV', 'PASS', 'NONE', 0, @de,
   'dbnorm-3312', NULL, '表结构与索引规范通过', 1,
   UTC_TIMESTAMP(3) - INTERVAL 39 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 35 MINUTE, 180000,
   NULL, NOW(3), NOW(3));