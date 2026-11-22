-- Dev-only demo state for the P3 production-control screens.
--
--   SET @scenario := 'ready';      -- 免审批：提交后直接「待执行」
--   SET @scenario := 'approval';   -- 封板期：提交后「待审批」，运维组长 + 部门经理双签
--   SET @scenario := 'rejected';   -- 已驳回：可演示「重新提交」
--
--   mysql --host=localhost --user=root --database=release_pipeline \
--         --default-character-set=utf8mb4 \
--         --execute="source D:/develop/workspace/version-pipeline/scripts/demo-prod.sql"
--
-- Then submit production from the release page (or POST .../submit-prod). The script rewinds the
-- demo item to 「待生产准入」 and removes its admission/approval history; it never touches the
-- build/test nodes of the execution.
--
-- English-only comments on the scenario keywords: this file is piped through the mysql client,
-- which on this Windows host mangles Chinese in --execute payloads.

SET @scenario := 'approval';
SET @item := 2;
SET @publish := 'PO-20261121-5976BF';

-- 1. Drop the admission history of that 发布单 (a resubmission generation is history too).
DELETE FROM approval
 WHERE order_id IN (SELECT id FROM release_order WHERE item_id = @item);
DELETE FROM admission_snapshot WHERE item_id = @item;

-- 2. Rewind the order and the item to 「待生产准入」.
UPDATE release_order
   SET status = 'INIT', oa_ticket_no = NULL, planned_release_time = NULL,
       window_result = NULL, deadline_at = NULL, row_version = row_version + 1
 WHERE item_id = @item;
UPDATE release_item
   SET status = 'GATE_PENDING', row_version = row_version + 1
 WHERE id = @item;

-- 3. No freezes by default; the approval scenario adds one ACTIVE global freeze that covers now.
DELETE FROM freeze_event;
INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id, start_at, end_at,
                          status, created_at)
SELECT 'GLOBAL', 0, '双十一大促封板', '大促期间生产变更需运维组长与部门经理双签', 'dev',
       UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, UTC_TIMESTAMP(3) + INTERVAL 1 DAY, 'ACTIVE',
       UTC_TIMESTAMP(3)
 WHERE @scenario = 'approval';

SELECT @scenario AS scenario, @item AS item, @publish AS publish_no;