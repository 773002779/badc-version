-- Task 14: idempotency of engine callbacks (tech design §4.3/§6.3).
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- CODING may deliver the same webhook event more than once. event_id is the natural key of an
-- engine event; the row is inserted BEFORE any side effect, so a duplicate delivery loses the
-- insert, is acknowledged with 200 and never advances the pipeline a second time.
-- received_at is stored as UTC DATETIME(3); payload_hash is the SHA-256 hex of the raw body
-- (the body is verified and fingerprinted before JSON parsing, never trusted from the parsed
-- document).

CREATE TABLE engine_event_dedup (
  event_id     VARCHAR(128) NOT NULL,
  received_at  DATETIME(3)  NOT NULL,
  payload_hash VARCHAR(64)  NOT NULL,
  PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;