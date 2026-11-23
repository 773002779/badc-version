-- P4-A (production rollback): the rollback record and the operator of the last production
-- execution. English-only DDL keeps Flyway parsing independent of the host charset.
--
-- release_order.executed_by is the person who last started (or resumed) the production execution
-- of the order. The rollback version list derives its "publisher" column from it; historical rows
-- keep NULL and the page shows a dash. It is not part of the order domain aggregate: it is an
-- operational attribution, written by ExecuteProdService in the execute transaction.
--
-- rollback_record is one production rollback (rollback design section 2-5). It is deliberately not
-- a release_order: a rollback does not create a new release, does not need an approval and must not
-- appear in the release-record list as another publish. It owns the source (the release that is
-- currently online: order/item/publish number/digest/commit) and the target version, so who rolled
-- back what to which image stays readable after the source order changed.
--
-- status: RUNNING (the rollback instance exists and has not finished), SUCCESS (the instance
-- reached DONE) or FAILED (the instance FAILED; failure_reason names the cause). The column is a
-- terminal compare-and-set in RollbackCompletionService, so two late events cannot both finish a
-- record. instance_id is NULL only for a record created by a future "plan" flow; the single-step
-- execution writes it in the same transaction that creates the instance.

ALTER TABLE release_order
  ADD COLUMN executed_by VARCHAR(32) NULL COMMENT 'operator of the last production execution';

CREATE TABLE rollback_record (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_id           BIGINT       NOT NULL,
  instance_id       BIGINT       NULL,
  pipeline_no       VARCHAR(32)  NULL,
  source_order_id   BIGINT       NULL,
  source_item_id    BIGINT       NULL,
  source_publish_no VARCHAR(32)  NULL,
  source_digest     VARCHAR(80)  NULL,
  source_commit     VARCHAR(64)  NULL,
  target_digest     VARCHAR(80)  NOT NULL,
  target_publish_no VARCHAR(32)  NULL,
  reason            VARCHAR(200) NOT NULL,
  requested_by      VARCHAR(32)  NOT NULL,
  status            VARCHAR(16)  NOT NULL COMMENT 'RUNNING / SUCCESS / FAILED',
  failure_reason    VARCHAR(255) NULL,
  created_at        DATETIME(3)  NOT NULL,
  finished_at       DATETIME(3)  NULL,
  KEY idx_rollback_unit (unit_id, created_at),
  KEY idx_rollback_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;