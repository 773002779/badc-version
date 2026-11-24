-- Dev-only demo state for the P4-B notification centre and the topbar bell.
--
-- Ten in-app rows for the local ADMIN identity `dev`: mixed read/unread, several event keys from
-- the backend enum, and object ids that really exist in this database (release orders, deployment
-- unit 2, freeze event 7). Timestamps are spread over the last two days so the newest-first page
-- and the 5-row bell dropdown have a believable order.
--
-- Usage (PowerShell):
--   $env:MYSQL_PWD='root'
--   mysql --host=localhost --user=root --database=release_pipeline --default-character-set=utf8mb4 `
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-notify.sql"
--
-- Idempotent: every row carries a DEMO-NTF- prefix in dedupe_key and recipient='dev'; re-running
-- deletes exactly those rows and inserts them again (which also resets their read state, useful
-- after the headless probe has marked some rows read).
--
-- English-only comments and strings on purpose: this file is piped through the mysql client, which
-- on this Windows host mangles Chinese in --execute payloads.

DELETE FROM notification
 WHERE recipient = 'dev'
   AND dedupe_key LIKE 'DEMO-NTF-%';

INSERT INTO notification
  (event_key, object_type, object_id, object_label, recipient, channel, title, body, status,
   read_at, attempts, failure_reason, dedupe_key, created_at, sent_at)
VALUES
  -- 1. newest, unread: an approval waiting for the caller
  ('APPROVAL_PENDING', 'ORDER', 'PO-20261121-5976BF', 'PO-20261121-5976BF', 'dev', 'INAPP',
   'Approval pending: production release PO-20261121-5976BF',
   'Step 1 (OPS_LEAD) is waiting for a signature. Open the release order to approve or reject it.',
   'SENT', NULL, 0, NULL,
   'DEMO-NTF-APPROVAL-PENDING-ORDER-5976BF',
   UTC_TIMESTAMP(3) - INTERVAL 8 MINUTE,
   UTC_TIMESTAMP(3) - INTERVAL 8 MINUTE),

  -- 2. unread: a build failure on the same order
  ('BUILD_FAILED', 'ORDER', 'PO-20261121-5976BF', 'PO-20261121-5976BF', 'dev', 'INAPP',
   'Build failed: PO-20261121-5976BF',
   'PKG_BUILD returned a non-zero exit code. Check the pipeline log, fix the branch and rerun.',
   'SENT', NULL, 0, NULL,
   'DEMO-NTF-BUILD-FAILED-ORDER-5976BF',
   UTC_TIMESTAMP(3) - INTERVAL 25 MINUTE,
   UTC_TIMESTAMP(3) - INTERVAL 25 MINUTE),

  -- 3. unread: test environment ready
  ('TEST_ENV_READY', 'ORDER', 'PO-20261121-5976BF', 'PO-20261121-5976BF', 'dev', 'INAPP',
   'Test environment ready: PO-20261121-5976BF',
   'The test deployment finished; test verification can start on the gate panel.',
   'SENT', NULL, 0, NULL,
   'DEMO-NTF-TEST-ENV-READY-ORDER-5976BF',
   UTC_TIMESTAMP(3) - INTERVAL 55 MINUTE,
   UTC_TIMESTAMP(3) - INTERVAL 55 MINUTE),

  -- 4. unread: a waiver waiting for control:approve; object_label is the publish number so the
  --    click target is /releases/PO-20261121-5976BF#gate
  ('WAIVER_PENDING', 'WAIVER', '1', 'PO-20261121-5976BF', 'dev', 'INAPP',
   'Gate waiver pending approval: PO-20261121-5976BF',
   'A waiver request for the image scan is waiting for a control:approve decision.',
   'SENT', NULL, 0, NULL,
   'DEMO-NTF-WAIVER-PENDING-WAIVER-1',
   UTC_TIMESTAMP(3) - INTERVAL 2 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 2 HOUR),

  -- 5. unread: a freeze release (the page target is /control)
  ('FREEZE_RELEASED', 'FREEZE', '7', 'Global release freeze', 'dev', 'INAPP',
   'Release freeze released',
   'The global release freeze was released; blocked orders can be submitted again.',
   'SENT', NULL, 0, NULL,
   'DEMO-NTF-FREEZE-RELEASED-FREEZE-7',
   UTC_TIMESTAMP(3) - INTERVAL 4 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 4 HOUR),

  -- 6. unread: a lock timeout on deployment unit 2
  ('LOCK_TIMEOUT_RELEASED', 'UNIT', '2', 'demo-api-1121222614', 'dev', 'INAPP',
   'Unit lock timeout released: demo-api-1121222614',
   'The unit lease exceeded its limit and was released; the in-flight order can be retried.',
   'SENT', NULL, 0, NULL,
   'DEMO-NTF-LOCK-TIMEOUT-UNIT-2',
   UTC_TIMESTAMP(3) - INTERVAL 7 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 7 HOUR),

  -- 7. unread: a recorded approval decision
  ('APPROVAL_DECIDED', 'ORDER', 'PO-20261121-5976BF', 'PO-20261121-5976BF', 'dev', 'INAPP',
   'Approval decided: PO-20261121-5976BF',
   'The OPS_LEAD step was approved. The order moved on to the next admission step.',
   'SENT', NULL, 0, NULL,
   'DEMO-NTF-APPROVAL-DECIDED-ORDER-5976BF',
   UTC_TIMESTAMP(3) - INTERVAL 9 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 9 HOUR),

  -- 8. read: production finished on the demo rollback order
  ('PROD_DONE', 'ORDER', 'PO-DEMO-RB-NEW', 'PO-DEMO-RB-NEW', 'dev', 'INAPP',
   'Production finished: PO-DEMO-RB-NEW',
   'All production batches completed. The image digest is now the online version of the unit.',
   'SENT', UTC_TIMESTAMP(3) - INTERVAL 23 HOUR, 0, NULL,
   'DEMO-NTF-PROD-DONE-ORDER-RB-NEW',
   UTC_TIMESTAMP(3) - INTERVAL 1 DAY,
   UTC_TIMESTAMP(3) - INTERVAL 1 DAY),

  -- 9. read: a rollback on deployment unit 2
  ('ROLLBACK_DONE', 'UNIT', '2', 'demo-api-1121222614', 'dev', 'INAPP',
   'Rollback finished: demo-api-1121222614',
   'The unit was rolled back to the previous image digest. Verify the environment before the next release.',
   'SENT', UTC_TIMESTAMP(3) - INTERVAL 27 HOUR, 0, NULL,
   'DEMO-NTF-ROLLBACK-DONE-UNIT-2',
   UTC_TIMESTAMP(3) - INTERVAL 1 DAY - INTERVAL 4 HOUR,
   UTC_TIMESTAMP(3) - INTERVAL 1 DAY - INTERVAL 4 HOUR),

  -- 10. oldest, read: a failed gate check
  ('CHECK_FAILED', 'ORDER', 'PO-DEMO-RB-OLD', 'PO-DEMO-RB-OLD', 'dev', 'INAPP',
   'Gate check failed: PO-DEMO-RB-OLD',
   'The image scan reported blocking issues. Fix them or request a waiver before admission.',
   'SENT', UTC_TIMESTAMP(3) - INTERVAL 2 DAY + INTERVAL 5 MINUTE, 0, NULL,
   'DEMO-NTF-CHECK-FAILED-ORDER-RB-OLD',
   UTC_TIMESTAMP(3) - INTERVAL 2 DAY,
   UTC_TIMESTAMP(3) - INTERVAL 2 DAY);

SELECT COUNT(*) AS demo_rows, SUM(read_at IS NULL) AS unread_rows
  FROM notification
 WHERE recipient = 'dev' AND dedupe_key LIKE 'DEMO-NTF-%';