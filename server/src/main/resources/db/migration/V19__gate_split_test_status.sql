-- P2 (T1 fix): the two gate dimensions get one column each.
-- English-only DDL keeps Flyway parsing independent of the host charset.
--
-- V7 stored BOTH gate dimensions in release_item.gate_status: the test outcome
-- (TEST_PENDING/TEST_PASS/TEST_FAIL/TEST_REVOKED) and the check outcome
-- (CHECK_RUNNING/CHECK_PASS/CHECK_WAIVED/CHECK_FAIL/CHECK_INVALID). The requirements call them
-- orthogonal and the page shows them in two columns, but one column can only hold one of them:
-- an evaluation that recorded the check result silently erased a revoking test decision. P2
-- therefore splits the dimension in two:
--
--   test_status : TEST_PENDING / TEST_PASS / TEST_FAIL / TEST_REVOKED
--   gate_status : CHECK_RUNNING / CHECK_PASS / CHECK_WAIVED / CHECK_FAIL / CHECK_INVALID
--
-- Existing rows hold the test dimension (a fresh item was TEST_PENDING, and the test was the only
-- writer), so they are remapped to the check dimension's "nothing decided yet" value. No history is
-- lost: last_test_pass_by/at keep the pass and test_fail_reason keeps a rejection.

ALTER TABLE release_item
  ADD COLUMN test_status VARCHAR(16) NOT NULL DEFAULT 'TEST_PENDING'
    COMMENT 'orthogonal test dimension: TEST_PENDING/TEST_PASS/TEST_FAIL/TEST_REVOKED'
  AFTER gate_status;

-- A pre-P2 item never ran a check, so its check dimension is "no result yet". The value is also what
-- a new item starts with, so the mapping is uniform.
UPDATE release_item
  SET gate_status = 'CHECK_RUNNING'
  WHERE gate_status LIKE 'TEST\_%';