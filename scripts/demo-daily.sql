-- Dev-only demo state for the P5-A daily release pages.
--
-- The demo change item (unit 2) gets two daily executions: one fully successful
-- BUILD -> DAILY_DEPLOY run with engine log links, and one failed run whose BUILD node stopped
-- at CODE_CHECK with ENGINE_UNAVAILABLE and whose DAILY_DEPLOY node never started.
-- They are not release orders: order_id = 0, no release order row, no unit lock, no item status
-- change.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-daily.sql"
--
-- Idempotent: the two instances carry the demo markers PL-DEMO-DAILY-DONE / PL-DEMO-DAILY-FAIL;
-- re-running deletes exactly those rows (children first) and inserts them again. The script reads
-- one existing item, its unit and one TEST DE, but never touches env_de, coding_task_binding,
-- release_order, release_item.status or the real records created through the API.
--
-- English-only content on purpose: this file is piped through the mysql client, which on this
-- Windows host mangles Chinese in --execute payloads.

SET @doneNo := 'PL-DEMO-DAILY-DONE';
SET @failNo := 'PL-DEMO-DAILY-FAIL';

-- 1. The demo item: the rollback demo item when present, otherwise the original unit-2 item.
SET @item := COALESCE(
  (SELECT id FROM release_item WHERE crid = 'CR-DEMO-RB-NEW' LIMIT 1),
  (SELECT id FROM release_item WHERE unit_id = 2 AND type = 'UNIT' ORDER BY id LIMIT 1)
);
SET @unit := (SELECT unit_id FROM release_item WHERE id = @item);
-- Daily falls back to the TEST DE when the unit has no DAILY DE (C24).
SET @de := COALESCE(
  (SELECT id FROM env_de WHERE unit_id = @unit AND env_kind = 'DAILY' ORDER BY id LIMIT 1),
  (SELECT id FROM env_de WHERE unit_id = @unit AND env_kind = 'TEST' ORDER BY id LIMIT 1)
);
SET @batch := COALESCE((SELECT deploy_batch_no FROM env_de WHERE id = @de), 0);

-- 2. Remove only the rows this script created on a previous run (children first).
DELETE nt FROM node_task nt
  JOIN pipeline_instance pi ON pi.id = nt.instance_id
 WHERE pi.pipeline_no IN (@doneNo, @failNo);
DELETE pn FROM pipeline_node pn
  JOIN pipeline_instance pi ON pi.id = pn.instance_id
 WHERE pi.pipeline_no IN (@doneNo, @failNo);
DELETE FROM pipeline_instance WHERE pipeline_no IN (@doneNo, @failNo);

-- 3. The instances: DAILY, no order, no lock, the demo item and its unit.
SET @doneEnd := UTC_TIMESTAMP(3) - INTERVAL 3 HOUR;
SET @doneStart := @doneEnd - INTERVAL 12 MINUTE;
SET @failEnd := UTC_TIMESTAMP(3) - INTERVAL 1 HOUR;
SET @failStart := @failEnd - INTERVAL 2 MINUTE;

INSERT INTO pipeline_instance
  (order_id, pipeline_no, exec_type, trigger_type, current_node, status, status_reason,
   image_digest, commit_sha, start_at, end_at, created_at, test_ready_at,
   item_id, unit_id, requested_by)
VALUES
  (0, @doneNo, 'DAILY', 'INITIAL', 'DAILY_DEPLOY', 'DONE', NULL,
   'sha256:demo-daily-3c9e5a7b1d4f2a6c8e0b3d5f7a9c1e4b6d8f0a2c4e6b9d1f',
   '3c9e5a7b1d4f2a6c8e0b3d5f7a9c1e4b6d8f0a2c', @doneStart, @doneEnd, @doneStart,
   @doneEnd - INTERVAL 6 MINUTE, @item, @unit, 'dev'),
  (0, @failNo, 'DAILY', 'INITIAL', 'BUILD', 'FAILED', NULL,
   NULL, NULL, @failStart, @failEnd, @failStart, NULL, @item, @unit, 'qa-zhang');

SET @doneInst := (SELECT id FROM pipeline_instance WHERE pipeline_no = @doneNo);
SET @failInst := (SELECT id FROM pipeline_instance WHERE pipeline_no = @failNo);

-- 4. Nodes: BUILD seq 0 then DAILY_DEPLOY seq 1 (the failed run keeps DEPLOY pending).
INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at, finished_at)
VALUES
  (@doneInst, 'BUILD', 0, 'SUCCESS', @doneStart, @doneStart + INTERVAL 8 MINUTE),
  (@doneInst, 'DAILY_DEPLOY', 1, 'SUCCESS', @doneStart + INTERVAL 8 MINUTE, @doneEnd),
  (@failInst, 'BUILD', 0, 'FAILED', @failStart, @failEnd),
  (@failInst, 'DAILY_DEPLOY', 1, 'PENDING', NULL, NULL);

-- 5. The BUILD chain is the same one an online release uses; the daily deploy is one DEPLOY task
--    per target DE, unbatched. external_url is the engine log link the drawer opens.
INSERT INTO node_task
  (instance_id, node_type, task_type, batch_no, target_id, status, attempt,
   external_id, external_url, result_summary, started_at, finished_at, duration_ms, updated_at)
VALUES
  (@doneInst, 'BUILD', 'CODE_CHECK', 0, 0, 'SUCCESS', 1,
   'demo-daily-check-1', 'https://coding.example.com/p/demo-release/build/demo-check/logs',
   'code check passed', @doneStart, @doneStart + INTERVAL 2 MINUTE, 120000,
   @doneStart + INTERVAL 2 MINUTE),
  (@doneInst, 'BUILD', 'PKG_BUILD', 0, 0, 'SUCCESS', 1,
   'demo-daily-pkg-1', NULL, 'package built', @doneStart + INTERVAL 2 MINUTE,
   @doneStart + INTERVAL 6 MINUTE, 240000, @doneStart + INTERVAL 6 MINUTE),
  (@doneInst, 'BUILD', 'IMG_BUILD', 0, 0, 'SUCCESS', 1,
   'demo-daily-img-1', 'https://coding.example.com/p/demo-release/build/demo-image/logs',
   'image built: sha256:demo-daily-3c9e5a7b', @doneStart + INTERVAL 6 MINUTE,
   @doneStart + INTERVAL 8 MINUTE, 120000, @doneStart + INTERVAL 8 MINUTE),
  (@doneInst, 'DAILY_DEPLOY', 'DEPLOY', @batch, @de, 'SUCCESS', 1,
   'demo-daily-deploy-1', 'https://coding.example.com/p/demo-release/deploy/demo-daily/logs',
   'daily deployed to demo-sit', @doneStart + INTERVAL 8 MINUTE, @doneEnd, 240000, @doneEnd),
  (@failInst, 'BUILD', 'CODE_CHECK', 0, 0, 'FAILED', 1,
   NULL, NULL, 'ENGINE_UNAVAILABLE: CODING connection is not configured (build.start)',
   @failStart, @failEnd, 120000, @failEnd),
  (@failInst, 'BUILD', 'PKG_BUILD', 0, 0, 'SKIPPED', 0,
   NULL, NULL, NULL, NULL, NULL, NULL, @failEnd),
  (@failInst, 'BUILD', 'IMG_BUILD', 0, 0, 'SKIPPED', 0,
   NULL, NULL, NULL, NULL, NULL, NULL, @failEnd),
  (@failInst, 'DAILY_DEPLOY', 'DEPLOY', @batch, @de, 'PENDING', 0,
   NULL, NULL, NULL, NULL, NULL, NULL, @failEnd);

SELECT @item AS demo_item, @unit AS demo_unit, @de AS demo_de,
       @doneNo AS done_pipeline, @failNo AS failed_pipeline;