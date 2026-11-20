package com.sf.release.domain.pipeline;

import java.util.Objects;

/**
 * Outcome of the rerun decision for one 变更项 (tech design §5.3, requirements §4.5):
 *
 * <ul>
 *   <li>{@code full} — rebuild from {@link NodeType#BUILD}; the previous image cannot be
 *       reused. Maps to {@code ItemEvent#RERUN_FULL} / {@link TriggerType#RERUN_FULL};</li>
 *   <li>{@code fromNode} — the node a new pipeline instance starts at. For an unchanged
 *       commit it is the first failed/incomplete node of the last instance (or
 *       {@code PROD_DEPLOY} when every node succeeded, i.e. production is the only step
 *       left); for a full rerun it is always {@code BUILD};</li>
 *   <li>{@code revokeTestPass} — a changed commit invalidates the previous test pass
 *       (requirements §4.5: 「测试通过」自动作废). The caller must obtain an explicit
 *       confirmation before applying it;</li>
 *   <li>{@code degraded} — the item has no {@code module_path}, so the comparison fell back to
 *       the whole repository. The decision is conservative and the caller should surface the
 *       risk (requirements §4.5 共库特殊处理).</li>
 * </ul>
 */
public record RerunPlan(boolean full, NodeType fromNode, boolean revokeTestPass,
                        boolean degraded) {

    public RerunPlan {
        Objects.requireNonNull(fromNode, "fromNode must not be null");
    }
}
