-- P3 review round (independent, I3): a paused pipeline instance may hold the deployment-unit lease
-- far longer than the four-hour TTL. Record when the instance was paused so the 60s renewer can stop
-- extending an abandoned pause instead of blocking the unit forever.
--
-- Existing rows keep NULL: the renewer treats NULL as "paused before this column existed" and renews
-- only for the bounded window counted from the row's last update, so a legacy pause cannot outlive the
-- rule either.

ALTER TABLE pipeline_instance
    ADD COLUMN paused_at DATETIME(3) NULL COMMENT 'when the instance entered PAUSED; cleared on resume';