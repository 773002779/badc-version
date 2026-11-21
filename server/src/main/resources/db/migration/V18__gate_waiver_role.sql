-- P2 (T9, decision D3): the 豁免 approver role.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- requirements FR-Q-03 says the waiver is approved by the "安全审核人" and that the permission is
-- configurable; the role -> permission mapping is data (V2), so this is a mapping row, not a code
-- change. OPS_LEAD is the operational reviewer who signs off on a medium finding; ADMIN already
-- holds gate:waive from the baseline seed.
--
-- The "no self-approval" rule is NOT expressed here: it is enforced by GateWaiverService, because
-- it needs the caller identity (and an ADMIN break-glass exception).

INSERT INTO role_permission (role, perm) VALUES ('OPS_LEAD', 'gate:waive');