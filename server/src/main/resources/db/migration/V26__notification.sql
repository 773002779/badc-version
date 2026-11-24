-- P4-B (GAP-02) notification: the in-app/WeCom outbox and the platform-wide channel setting.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- notification is one row per (event, recipient, channel) of the design section 2. The in-app row is
-- delivered the moment it is created (status SENT, read_at NULL until the recipient reads it); a
-- WeCom row is created PENDING and delivered by NotificationDispatchJob. status is PENDING / SENT /
-- FAILED. dedupe_key is event_key|object_type|object_id|recipient|channel|ref and is unique, so a
-- duplicated trigger (a replayed pipeline event, a retried notification) inserts nothing: the unique
-- key is the idempotency guard, not the caller.
--
-- The row keeps the display fields it needs after the object changed (title/body are plain text;
-- the WeCom markdown is composed from the same values at send time), the delivery attempts of the
-- WeCom channel and the failure reason (truncated to the column width by the service).
--
-- notification_setting is a single row (id = 1): the WeCom robot switch and its webhook. The webhook
-- is a sensitive deployment configuration and is returned as-is to setting:manage (the page edits
-- it), but the send log must only name its host.

CREATE TABLE notification (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_key      VARCHAR(64)  NOT NULL COMMENT 'BUILD_FAILED ... FREEZE_APPLIED',
  object_type    VARCHAR(32)  NOT NULL COMMENT 'ORDER / ITEM / UNIT / FREEZE / WAIVER / INSTANCE',
  object_id      VARCHAR(64)  NOT NULL COMMENT 'publish number / item id / unit id / freeze id ...',
  object_label   VARCHAR(200) NULL,
  recipient      VARCHAR(32)  NOT NULL,
  channel        VARCHAR(16)  NOT NULL COMMENT 'INAPP / WECOM',
  title          VARCHAR(200) NOT NULL,
  body           VARCHAR(2000) NOT NULL,
  status         VARCHAR(16)  NOT NULL COMMENT 'PENDING / SENT / FAILED',
  read_at        DATETIME(3)  NULL,
  attempts       INT          NOT NULL DEFAULT 0,
  failure_reason VARCHAR(512) NULL,
  dedupe_key     VARCHAR(200) NOT NULL,
  created_at     DATETIME(3)  NOT NULL,
  sent_at        DATETIME(3)  NULL,
  UNIQUE KEY uk_notification_dedupe (dedupe_key),
  KEY idx_notification_recipient (recipient, created_at),
  KEY idx_notification_pending (status, channel, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_setting (
  id            TINYINT PRIMARY KEY,
  wecom_enabled TINYINT      NOT NULL DEFAULT 0,
  wecom_webhook VARCHAR(512) NULL,
  updated_by    VARCHAR(32)  NULL,
  updated_at    DATETIME(3)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO notification_setting (id, wecom_enabled, wecom_webhook, updated_at)
VALUES (1, 0, NULL, NOW(3));