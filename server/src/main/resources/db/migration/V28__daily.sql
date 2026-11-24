-- P5-A (GAP-04) daily release: a simplified BUILD -> DAILY_DEPLOY pipeline that is not a
-- 发布单 (order_id = 0), holds no 部署单元 lock and never changes the 变更项 status.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- Only a DAILY instance fills item_id/unit_id/requested_by: an ONLINE instance is still resolved
-- through its release order (order -> item -> unit), so every existing online query keeps working
-- unchanged and its three new columns stay NULL.
--
-- idx_instance_item is the only access path the daily pages need: the records list of one
-- 变更项 is "every DAILY instance of this item, newest first". pipeline_no stays globally unique
-- (uk_pipeline_no), so the daily detail is still addressable by its pipeline number.

ALTER TABLE pipeline_instance
  ADD COLUMN item_id      BIGINT      NULL COMMENT 'daily: the change item, NULL for online',
  ADD COLUMN unit_id      BIGINT      NULL COMMENT 'daily: the unit, NULL for online (order carries it)',
  ADD COLUMN requested_by VARCHAR(32) NULL COMMENT 'daily: who started it (online uses order.executed_by)',
  ADD KEY idx_instance_item (item_id);