-- P4-C (GAP-03) audit trail: who did what to which object, searchable and exportable.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- audit_log is append-only: the application inserts rows and never updates or deletes them (the
-- retention policy belongs to P7). Each row names the actor, the action code and label source, the
-- object, a one-line summary, the caller's reason, the changed fields only (before_json/after_json
-- are field subsets, not row snapshots) and the request trace id.
--
-- Scope columns make the fail-closed read model possible: scope_unit_id for unit-scoped actions,
-- scope_subsystem_id for system-level actions, both NULL only for platform-level settings (which
-- only an ALL-scope caller may read). The indexes match the page ordering
-- (created_at DESC, id DESC) and the three ways the audit page is filtered: by object, by actor and
-- by unit.
--
-- Both JSON columns are nullable on purpose: a newly created object has no "before" state, and an
-- action that only reads a fact has neither.

CREATE TABLE audit_log (
  id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor              VARCHAR(32)  NOT NULL COMMENT 'X-User-Id of the caller (SYSTEM for jobs)',
  action             VARCHAR(48)  NOT NULL COMMENT 'PUBLISH / SUBMIT_PROD / ...',
  object_type        VARCHAR(32)  NOT NULL COMMENT 'ORDER / ITEM / UNIT / SUBSYSTEM / TASK / FREEZE / WAIVER / ROLLBACK / SETTING',
  object_id          VARCHAR(64)  NOT NULL COMMENT 'publish number / id / setting scope',
  object_label       VARCHAR(200) NULL,
  summary            VARCHAR(512) NOT NULL COMMENT 'one-line human summary',
  reason             VARCHAR(512) NULL,
  before_json        JSON         NULL,
  after_json         JSON         NULL,
  scope_unit_id      BIGINT       NULL COMMENT 'unit the object belongs to, for scope filtering',
  scope_subsystem_id BIGINT       NULL,
  trace_id           VARCHAR(64)  NULL,
  created_at         DATETIME(3)  NOT NULL,
  KEY idx_audit_object (object_type, object_id, created_at),
  KEY idx_audit_actor (actor, created_at),
  KEY idx_audit_unit (scope_unit_id, created_at),
  KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;