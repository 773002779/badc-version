-- P6-D (GAP-11) emergency release: the admin break-glass exit of a release that is still waiting
-- for a signature, plus the history of every use of it. English-only DDL keeps Flyway parsing
-- independent of the host charset.
--
-- release_order carries the read marker so the release detail / the record list can paint the red
-- "紧急放行" banner or chip without joining a second table (design §2). The four columns are written
-- once, in the same transaction that voids the pending approvals and moves the item to READY:
--   emergency_release  1 = this order was released via J11 (0 = never)
--   emergency_reason   the mandatory 1..200 character operator reason
--   emergency_by       the administrator who pressed the button
--   emergency_at       when it happened
-- They are never cleared: the design has no "undo emergency release"; a later release rerun keeps
-- the marker as the order's most recent emergency fact (design §6).
--
-- emergency_release is the append-only history, one row per use. It exists separately from the
-- order columns so an order that is rerun and emergency-released again keeps both occurrences; the
-- order columns stay the "most recent" read used by the pages.

ALTER TABLE release_order
  ADD COLUMN emergency_release TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = released via J11',
  ADD COLUMN emergency_reason  VARCHAR(512) NULL,
  ADD COLUMN emergency_by      VARCHAR(32)  NULL,
  ADD COLUMN emergency_at      DATETIME(3)  NULL;

CREATE TABLE emergency_release (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id   BIGINT       NOT NULL,
  publish_no VARCHAR(32)  NOT NULL,
  reason     VARCHAR(512) NOT NULL,
  actor      VARCHAR(32)  NOT NULL,
  created_at DATETIME(3)  NOT NULL,
  KEY idx_emergency_order (order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;