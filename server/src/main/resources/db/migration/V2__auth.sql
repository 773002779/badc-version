-- Phase 1 authentication: local identities via the temporary X-User-Id header until
-- SSO is confirmed. The role -> permission mapping is data, not code: it can be changed
-- without a deploy. Only UNIT scope rows are resolved in phase 1; SUBSYSTEM rows become
-- meaningful in Task 5 when deployment units exist.
CREATE TABLE user_role (
  user_id VARCHAR(32) NOT NULL,
  role    VARCHAR(32) NOT NULL,
  PRIMARY KEY (user_id, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE role_permission (
  role VARCHAR(32) NOT NULL,
  perm VARCHAR(64) NOT NULL,
  PRIMARY KEY (role, perm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_scope (
  user_id    VARCHAR(32) NOT NULL,
  scope_type VARCHAR(16) NOT NULL COMMENT 'UNIT / SUBSYSTEM',
  scope_id   BIGINT      NOT NULL,
  PRIMARY KEY (user_id, scope_type, scope_id),
  KEY idx_user_scope_lookup (user_id, scope_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Baseline role -> permission seed. OPS / OPS_LEAD deliberately do not receive
-- release:publish: C20 forbids them from initiating code version releases, and
-- PermissionInterceptor enforces that hard rule even if a mapping row is added later.
INSERT INTO role_permission (role, perm) VALUES
  ('ADMIN', 'task:create'),
  ('ADMIN', 'item:create'),
  ('ADMIN', 'release:publish'),
  ('ADMIN', 'release:daily'),
  ('ADMIN', 'gate:test-pass'),
  ('ADMIN', 'gate:test-revoke'),
  ('ADMIN', 'gate:waive'),
  ('ADMIN', 'control:submit-prod'),
  ('ADMIN', 'control:execute-prod'),
  ('ADMIN', 'control:approve'),
  ('ADMIN', 'control:freeze'),
  ('ADMIN', 'control:unfreeze'),
  ('ADMIN', 'rollback:create'),
  ('ADMIN', 'rollback:execute'),
  ('ADMIN', 'lock:force-release'),
  ('ADMIN', 'setting:manage'),

  ('DEV', 'task:create'),
  ('DEV', 'item:create'),
  ('DEV', 'release:publish'),
  ('DEV', 'release:daily'),
  ('DEV', 'control:submit-prod'),

  ('TEST', 'gate:test-pass'),
  ('TEST', 'gate:test-revoke'),

  ('OPS', 'control:execute-prod'),
  ('OPS', 'rollback:execute'),

  ('OPS_LEAD', 'control:approve'),
  ('OPS_LEAD', 'control:unfreeze'),

  ('DEPT_MANAGER', 'control:approve'),
  ('DEPT_MANAGER', 'control:freeze'),

  ('PRODUCT', 'task:create'),

  ('OWNER', 'task:create'),
  ('OWNER', 'item:create'),
  ('OWNER', 'release:publish'),
  ('OWNER', 'release:daily'),
  ('OWNER', 'control:submit-prod'),
  ('OWNER', 'rollback:create');
