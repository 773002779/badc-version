package com.sf.release.domain.pipeline;

/**
 * What caused a {@link PipelineInstance} to be created (tech design §4.2
 * {@code trigger_type}, §5.3): the first run, a rerun from the failed node, a full rerun after
 * the commit changed, or a rollback execution.
 */
public enum TriggerType {
    INITIAL,
    RERUN_FROM_NODE,
    RERUN_FULL,
    ROLLBACK
}