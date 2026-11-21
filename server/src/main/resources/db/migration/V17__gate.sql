-- P2 (T1): the gate data model of a 变更项 — check results and waivers.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- gate_check is the platform's own record of one check of one item. There is no check
-- catalogue table (phase 1 derives the seven checks from CheckType), so check_kind carries the
-- CheckType name and stage the stage it belongs to (IMAGE / ENV). The unique key is
-- (item_id, check_kind, stage): a second report for the same check OVERWRITES the row instead of
-- producing a duplicate, which is what makes the callback idempotent (tech design §7.1).
--
-- status is the platform vocabulary: PENDING / RUNNING / PASS / FAIL / TIMEOUT / INVALID /
-- UNKNOWN. UNKNOWN is deliberately not terminal: an engine outage must read as "check not
-- finished", never as "check passed". The engine's own status word is mapped through
-- coding-endpoints.yml.
--
-- severity is the highest defect level this check reported (NONE / S3 低危 / S2 中危 /
-- S1 高危 / S0 严重). The gate decision uses the highest severity of the whole 发布单
-- (requirements FR-Q-02), not a per-check pass/fail.
--
-- scanned_object records WHAT was scanned (commit sha, image digest or DE name), so a rerun
-- with a different image can tell "this result is stale" from "this result is current".
-- attempt counts how many times the check was started for the item; a refresh increments it.
--
-- The (status, deadline_at) index backs the reconcile poll and the per-check timeout (D6:
-- 30 minutes by default).

CREATE TABLE gate_check (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id        BIGINT       NOT NULL,
  order_id       BIGINT       NULL,
  instance_id    BIGINT       NULL COMMENT 'the pipeline instance that ran the check',
  check_kind     VARCHAR(32)  NOT NULL COMMENT 'CheckType name, for example SONAR',
  stage          VARCHAR(8)   NOT NULL COMMENT 'IMAGE / ENV',
  status         VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/PASS/FAIL/TIMEOUT/INVALID/UNKNOWN',
  severity       VARCHAR(8)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE/S3/S2/S1/S0',
  issues         INT          NOT NULL DEFAULT 0 COMMENT 'problem count reported by the engine',
  scanned_object VARCHAR(128) NULL COMMENT 'commit sha, image digest or DE name',
  external_id    VARCHAR(128) NULL COMMENT 'engine side check id',
  report_url     VARCHAR(512) NULL,
  summary        VARCHAR(512) NULL,
  attempt        INT          NOT NULL DEFAULT 0,
  started_at     DATETIME(3)  NULL,
  finished_at    DATETIME(3)  NULL,
  duration_ms    BIGINT       NULL,
  deadline_at    DATETIME(3)  NULL COMMENT 'per-check timeout (started_at + 30min)',
  created_at     DATETIME(3)  NOT NULL,
  updated_at     DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_item_check (item_id, check_kind, stage),
  KEY idx_instance (instance_id),
  KEY idx_status_deadline (status, deadline_at),
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- gate_waiver is the 豁免单 of one 变更项 (requirements FR-Q-03): one waiver covers the item's
-- medium-severity findings for ONE snapshot (commit + image digest), not one waiver per check.
-- status is PENDING / APPROVED / REJECTED / EXPIRED / CANCELED. Only APPROVED and unexpired
-- waivers count; an obsolete snapshot (commit or digest changed) stops counting too, which is
-- why both values are stored here instead of being re-read from the order.
--
-- The approving user must differ from the requesting user (no self-approval, C19 spirit); that
-- rule is enforced in the application service because it needs the caller identity.

CREATE TABLE gate_waiver (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id       BIGINT       NOT NULL,
  order_id      BIGINT       NULL,
  status        VARCHAR(16)  NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED/CANCELED',
  reason        VARCHAR(512) NOT NULL COMMENT 'why the medium findings are acceptable',
  impact        VARCHAR(512) NULL COMMENT 'impact assessment supplied by the requester',
  requested_by  VARCHAR(32)  NOT NULL,
  requested_at  DATETIME(3)  NOT NULL,
  approved_by   VARCHAR(32)  NULL,
  approved_at   DATETIME(3)  NULL,
  decide_reason VARCHAR(512) NULL COMMENT 'rejection reason',
  expire_at     DATETIME(3)  NOT NULL COMMENT 'the waiver stops counting after this instant',
  commit_sha    VARCHAR(64)  NULL COMMENT 'snapshot the waiver covers',
  image_digest  VARCHAR(80)  NULL COMMENT 'snapshot the waiver covers',
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  KEY idx_item_status (item_id, status),
  KEY idx_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The reason a test failed (requirements FR-Q-04: everyone can read why). gate_status carries
-- TEST_FAIL, but the DDL had no column for the explanation, so the tester's note had nowhere to
-- live. It is cleared by a new pass and by a revoke.

ALTER TABLE release_item
  ADD COLUMN test_fail_reason VARCHAR(512) NULL COMMENT 'why the tester marked the item failed'
  AFTER last_test_pass_at;