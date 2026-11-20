-- Task 6: CODING tenant connections with encrypted credentials.
-- English-only DDL: Flyway parses the file with the JVM/database default charset on this
-- Windows host, so Chinese comments are not reliable there.
--
-- scope is GLOBAL or SUBSYSTEM. subsystem_id participates in the unique key and MySQL
-- treats NULLs as distinct there, so GLOBAL rows store the sentinel 0 instead of NULL.
-- credential_cipher stores Base64(iv || ciphertext || GCM-128-tag); the plaintext token is
-- never persisted.

CREATE TABLE coding_conn (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope             VARCHAR(16)  NOT NULL COMMENT 'GLOBAL / SUBSYSTEM',
  subsystem_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '0 for GLOBAL, subsystem id for SUBSYSTEM',
  base_url          VARCHAR(255) NOT NULL,
  team              VARCHAR(64)  NOT NULL,
  project           VARCHAR(64)  NOT NULL,
  credential_cipher TEXT         NOT NULL COMMENT 'AES-GCM Base64(iv || ciphertext || tag)',
  created_at        DATETIME(3)  NOT NULL,
  updated_at        DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_coding_conn_scope (scope, subsystem_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
