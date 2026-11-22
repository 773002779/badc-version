-- P3 (T3): the admission snapshot records WHO submitted the release, so the approval flow can
-- refuse self-approval (40008, design 4.4: the submitter may not sign their own release). The
-- submitter is immutable per snapshot: a resubmission writes a new one, exactly like every other
-- admission fact. English-only DDL keeps Flyway parsing independent of the host charset.
--
-- The column is nullable on purpose: snapshots written before this migration (development and test
-- schemas only) have no submitter, and an approval of such a snapshot cannot check the rule. It is
-- never backfilled with a guess -- inventing a submitter would be worse than not knowing one.

ALTER TABLE admission_snapshot
  ADD COLUMN submitted_by VARCHAR(32) NULL
    COMMENT 'the user id that submitted/resubmitted the release' AFTER item_id;