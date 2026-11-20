-- Task 14 fix round 1: durable record of a failed 变更项 transition (tech design §6.4).
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- Pipeline events are published inside the orchestrator transaction, but the application
-- listener is a notification side effect: it must never break the advance. A transition that
-- cannot be applied (corrupt row, unexpected DB error, transient state) is therefore recorded
-- here instead of only logged, and the 30s reconcile retries it. attempts counts the retries;
-- after three failed attempts the row is left unresolved for operations (resolved_at NULL).

CREATE TABLE pipeline_item_failure (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  instance_id   BIGINT       NOT NULL,
  item_id       BIGINT       NOT NULL,
  event_name    VARCHAR(32)  NOT NULL COMMENT 'ItemEvent name to replay',
  error_message VARCHAR(512) NOT NULL,
  attempts      INT          NOT NULL DEFAULT 0,
  created_at    DATETIME(3)  NOT NULL,
  resolved_at   DATETIME(3)  NULL COMMENT 'set when a retry applied the transition',
  KEY idx_unresolved (resolved_at, attempts),
  KEY idx_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;