-- Task 13: durable claim for the once-only TEST environment ready event.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- The ready event must be published exactly once across application instances: the completion
-- hook that observes "all TEST DE deploys succeeded" runs outside the distributed instance
-- lock, so an in-process flag cannot prevent a second application instance from publishing.
-- test_ready_at is the compare-and-set guard (NULL = never published yet) and records when the
-- test environment became ready.

ALTER TABLE pipeline_instance
  ADD COLUMN test_ready_at DATETIME(3) NULL COMMENT 'first TEST env ready publication claim';