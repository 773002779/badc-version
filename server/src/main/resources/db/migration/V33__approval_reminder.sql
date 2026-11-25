-- P6-E (GAP-12) approval SLA settings: one more section of the generic app_setting key/value table
-- (V30), no new table.
--
-- APPROVAL {"enabled":true,"remindHours":12,"escalateHours":24}
--
--   enabled        false = the 10-minute scan sends nothing at all (the switch is respected before
--                  the due query, so a disabled scan is also a cheap scan)
--   remindHours    the wait after which the pending step's role is reminded (1..72)
--   escalateHours  the wait after which ADMIN users + the system owner are escalated (2..168 and
--                  strictly greater than remindHours)
--
-- The two thresholds are the requirement's 12h/24h points, made configurable (J7 / FR-G-04). An
-- unreadable/invalid section falls back to enabled 12/24 in the application, so a broken row can
-- never stop every reminder silently.
--
-- There is deliberately no row for a reminder "recipient group": the reminder audience stays the
-- effective approver list of the pending step's role (P6-B), falling back to the system owner, and
-- the escalation audience stays ADMIN + system owner (P6-E design §5).

INSERT INTO app_setting (setting_key, value_json, updated_at)
VALUES ('APPROVAL', '{"enabled":true,"remindHours":12,"escalateHours":24}', NOW(3));
