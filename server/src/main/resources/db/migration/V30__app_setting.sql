-- P6-B (GAP-08) platform settings: one generic key/value table for the settings sections that have a
-- real consumer and are therefore worth persisting.
--
-- app_setting is deliberately a generic key/value store instead of one table per settings section:
-- the three sections delivered here (GATE / APPROVER / LOCK) are read and written as a whole, and a
-- later section can be added without another migration. Each section has one typed view in the
-- application layer; the JSON is the stored representation, not a query surface.
--
-- GATE     {"enabledChecks":[CheckType names]}      default: all seven checks enabled
-- APPROVER {"opsLead":[user ids],"deptManager":[user ids]} default: empty, meaning "keep the
--          existing user_role rows" (the role membership is the authority, see P6-B design §2.2)
-- LOCK     {"pauseBoundHours":n}                    default: 168 hours (the pre-P6-B seven days)
--
-- There is intentionally no row for DICT or role_permission: no consumer exists for a dictionary,
-- and editing the role -> permission map is a permission-model change, not a settings-page edit
-- (design §6).
--
-- The lease TTL itself is not configurable: Redis/DB lease durations are written at acquisition
-- time, so only the abandoned-pause bound is exposed (design §2.3).

CREATE TABLE app_setting (
  setting_key VARCHAR(32)  PRIMARY KEY COMMENT 'GATE / APPROVER / LOCK',
  value_json  JSON         NOT NULL,
  updated_by  VARCHAR(32)  NULL,
  updated_at  DATETIME(3)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO app_setting (setting_key, value_json, updated_at) VALUES
  ('GATE', '{"enabledChecks":["SONAR","COMPONENT_VULN","ARCHITECTURE","VERSION_DEFECT","SECURITY_DYNAMIC","AUTO_TEST","DB_NORM"]}', NOW(3)),
  ('APPROVER', '{"opsLead":[],"deptManager":[]}', NOW(3)),
  ('LOCK', '{"pauseBoundHours":168}', NOW(3));