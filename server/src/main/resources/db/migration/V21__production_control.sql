-- P3 (T1): the production-control foundation - release windows, freezes, the approval chain and
-- the admission snapshot. English-only DDL keeps Flyway parsing independent of the host charset.
--
-- release_window is the header of a release window (requirements 5.6, tech design 4.1). One row
-- per scope: GLOBAL is the platform default, SUBSYSTEM overrides it for one system. A GLOBAL row
-- stores the sentinel subsystem_id = 0 instead of NULL, because MySQL treats NULLs as distinct in
-- a unique key and uk_window_scope would otherwise allow several "global" rows.
--
-- enabled has two different meanings that must not be confused:
--   * no row at all  = "not configured" = OUTSIDE the window (a release still needs approval);
--   * enabled = 0    = the constraint is switched off = ALWAYS inside the window.
-- The application service documents both; the page states which one applies.
--
-- timezone is the zone the weekday rules are written in (Asia/Shanghai by default). Rules store a
-- local time of day, never an absolute instant, so a daylight-saving change elsewhere cannot move
-- a window.

CREATE TABLE release_window (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope        VARCHAR(16) NOT NULL COMMENT 'GLOBAL / SUBSYSTEM',
  subsystem_id BIGINT      NOT NULL DEFAULT 0
    COMMENT '0 for GLOBAL (sentinel; MySQL unique keys treat NULLs as distinct)',
  enabled      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1 = constraint on; 0 = always inside',
  timezone     VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA zone of the rules',
  created_at   DATETIME(3) NOT NULL,
  updated_at   DATETIME(3) NOT NULL,
  UNIQUE KEY uk_window_scope (scope, subsystem_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- window_rule is one recurring weekly slot of a window. day_of_week is the ISO weekday
-- (1 = Monday .. 7 = Sunday). When end_time is before start_time the slot crosses midnight:
-- 22:00-02:00 covers 22:00-24:00 on that weekday AND 00:00-02:00 on the following day. A
-- degenerate slot (start == end) is rejected by the service, because it would mean both
-- "zero length" and "all day".

CREATE TABLE window_rule (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  window_id   BIGINT  NOT NULL,
  day_of_week TINYINT NOT NULL COMMENT 'ISO weekday: 1 Monday .. 7 Sunday',
  start_time  TIME    NOT NULL COMMENT 'inclusive',
  end_time    TIME    NOT NULL COMMENT 'exclusive; before start_time means it crosses midnight',
  KEY idx_rule_window (window_id, day_of_week)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- window_exception overrides the weekday rules for one calendar date (holiday, release freeze of
-- the platform, one-off evening release). CLOSED closes the whole date. OPEN without times opens
-- the whole date; OPEN with times opens only that span (it may cross midnight like a rule). One
-- exception per window per date; the service treats it as authoritative for that date.

CREATE TABLE window_exception (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  window_id  BIGINT     NOT NULL,
  the_date   DATE       NOT NULL,
  mode       VARCHAR(8) NOT NULL COMMENT 'OPEN / CLOSED',
  start_time TIME       NULL COMMENT 'OPEN only; NULL with end_time NULL means the whole day',
  end_time   TIME       NULL COMMENT 'OPEN only; exclusive',
  UNIQUE KEY uk_window_date (window_id, the_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- freeze_event is one production freeze (tech design 4.2). A freeze is in force when
-- status = ACTIVE and start_at <= now < end_at; the range predicate is what idx_freeze_range
-- backs. scope GLOBAL blocks every system, SUBSYSTEM blocks one (subsystem_id is then NOT NULL;
-- a GLOBAL row keeps it NULL so "which system" cannot be mistaken for 0).
--
-- status: ACTIVE / RELEASED (ended by a person, released_by + released_at are set) / EXPIRED
-- (the end_at passed and the daily scan closed it). A freeze never deletes rows: the history is
-- the evidence of why a release needed a second signature.

CREATE TABLE freeze_event (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope        VARCHAR(16)  NOT NULL COMMENT 'GLOBAL / SUBSYSTEM',
  subsystem_id BIGINT       NULL COMMENT 'NULL for a GLOBAL freeze',
  reason       VARCHAR(512) NOT NULL,
  impact       VARCHAR(512) NULL COMMENT 'impact assessment written by the initiator',
  initiator_id VARCHAR(32)  NOT NULL,
  start_at     DATETIME(3)  NOT NULL,
  end_at       DATETIME(3)  NOT NULL COMMENT 'exclusive upper bound',
  status       VARCHAR(16)  NOT NULL COMMENT 'ACTIVE / RELEASED / EXPIRED',
  released_by  VARCHAR(32)  NULL,
  released_at  DATETIME(3)  NULL,
  created_at   DATETIME(3)  NOT NULL,
  KEY idx_freeze_range (status, start_at, end_at),
  KEY idx_freeze_subsystem (scope, subsystem_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- approval is one step of the graded approval chain of one release order (tech design 4.4). A step names a ROLE,
-- not a person: anybody holding that role may sign it. step_no is 1-based and sequential;
-- uk_approval_step makes "one step number exists once per order" a database fact, and
-- idx_approval_order reads the chain in order.
--
-- status: PENDING / APPROVED / REJECTED / CANCELED. Only the lowest PENDING step may be decided
-- (sequential approval). A rejection terminates the chain: the rejected step becomes REJECTED and the
-- later PENDING steps become CANCELED, so nobody keeps waiting for a signature that cannot matter.

CREATE TABLE approval (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id   BIGINT       NOT NULL,
  step_no    INT          NOT NULL COMMENT '1-based, sequential',
  role       VARCHAR(32)  NOT NULL COMMENT 'OPS_LEAD / DEPT_MANAGER',
  status     VARCHAR(16)  NOT NULL COMMENT 'PENDING / APPROVED / REJECTED / CANCELED',
  decided_by VARCHAR(32)  NULL,
  decided_at DATETIME(3)  NULL,
  comment    VARCHAR(512) NULL COMMENT 'approval note or mandatory rejection reason',
  created_at DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_approval_step (order_id, step_no),
  KEY idx_approval_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- admission_snapshot is the immutable record of one production-admission decision (tech design 4.5). It is
-- written once when the change item is submitted and never updated: a resubmission produces a new row.
-- The page, the executor and the audit all read the same snapshot, so "why was this admitted"
-- cannot drift with the current tables.
--
-- Columns that must be queried or compared are real columns; lists that are only evidence
-- (freeze_ids, waiver_ids, chain_json) are TEXT holding a JSON array/object. planned_release_time
-- is the time the window was evaluated at; the executor does not re-evaluate it (design 4.6).

CREATE TABLE admission_snapshot (
  id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id             BIGINT      NOT NULL,
  item_id              BIGINT      NOT NULL,
  planned_release_time DATETIME(3) NOT NULL COMMENT 'the instant the window decision used',
  in_window            TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1 = inside the release window',
  window_id            BIGINT      NULL COMMENT 'the release_window row that decided it',
  frozen               TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1 = a freeze was in force',
  freeze_ids           TEXT        NULL COMMENT 'JSON array of freeze_event ids in force',
  max_severity         VARCHAR(8)  NULL COMMENT 'NONE / S3 / S2 / S1 / S0',
  check_conclusion     VARCHAR(16) NULL COMMENT 'the gate conclusion at submission',
  waiver_ids           TEXT        NULL COMMENT 'JSON array of gate_waiver ids that counted',
  test_conclusion      VARCHAR(24) NULL COMMENT 'the test gate conclusion at submission',
  test_pass_by         VARCHAR(32) NULL,
  oa_ticket_no         VARCHAR(64) NULL COMMENT 'copy of the OA ticket for audit',
  image_digest         VARCHAR(80) NULL COMMENT 'the image digest this admission is for',
  chain_json           TEXT        NULL COMMENT 'the required approval chain as JSON',
  created_at           DATETIME(3) NOT NULL,
  KEY idx_snapshot_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;