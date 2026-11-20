package com.sf.release.domain.pipeline;

import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.task.ReleaseItem;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides the scope of a 重新发布 (tech design §5.3, requirements §4.5 C15) from the commit the
 * item was built from and the head of its branch.
 *
 * <p><strong>The comparison uses the item's {@code base_commit}, never an instance commit
 * field.</strong> {@code pipeline_instance.commit_sha} is what an engine trigger reported while
 * building; the baseline of the item is what a rerun has to compare against (Task 12/15
 * hand-off).
 *
 * <p><strong>Decision table.</strong>
 * <table>
 *   <caption>Inputs and outcome</caption>
 *   <tr><th>base == head</th><th>module_path</th><th>hasChangesUnder</th><th>full</th>
 *       <th>fromNode</th><th>revokeTestPass</th><th>degraded</th></tr>
 *   <tr><td>yes</td><td>any</td><td>not asked</td><td>false</td><td>first failed/incomplete
 *       node, else PROD_DEPLOY</td><td>false</td><td>false</td></tr>
 *   <tr><td>no</td><td>set</td><td>false</td><td>false</td><td>first failed/incomplete node,
 *       else PROD_DEPLOY</td><td>false</td><td>false</td></tr>
 *   <tr><td>no</td><td>set</td><td>true</td><td>true</td><td>BUILD</td><td>true</td>
 *       <td>false</td></tr>
 *   <tr><td>no</td><td>null</td><td>whole-repo fallback</td><td>true</td><td>BUILD</td>
 *       <td>true</td><td>true</td></tr>
 *   <tr><td>no</td><td>set but base null</td><td>cannot filter</td><td>true</td><td>BUILD</td>
 *       <td>true</td><td>true</td></tr>
 * </table>
 *
 * <p>The {@code module_path} filter is the shared-repository rule: a commit that only touches
 * other modules is not a change for this item and must not revoke its test pass (review focus
 * 4). With no module path the whole-repository comparison is the only possible one, so the
 * plan is flagged {@code degraded}.
 *
 * <p><strong>Resume point.</strong> The first node that is {@code FAILED}, or not yet
 * {@code SUCCESS}/{@code SKIPPED}, resumes the pipeline. When every node succeeded the last node
 * ({@code PROD_DEPLOY}) is the only remaining step — this is the fallback required by the plan
 * brief rather than an error.
 *
 * <p><strong>Safety.</strong> A blank head or a missing base commit cannot prove that the code
 * is unchanged, so the planner errs on the side of a full rerun (and reports degradation).
 */
public final class RerunPlanner {

    private final GitCommitProbe commitProbe;
    private final LastInstanceLookup lastInstances;

    public RerunPlanner(GitCommitProbe commitProbe, LastInstanceLookup lastInstances) {
        this.commitProbe = Objects.requireNonNull(commitProbe, "commitProbe must not be null");
        this.lastInstances = Objects.requireNonNull(lastInstances,
            "lastInstances must not be null");
    }

    /**
     * Decides the rerun scope of {@code item} for the observed branch head {@code headSha},
     * looking up the item's last instance itself.
     *
     * @param headSha the head of the item's source branch as reported by the engine; {@code null}
     *                or blank means the engine could not report it
     */
    public RerunPlan decide(ReleaseItem item, String headSha) {
        Objects.requireNonNull(item, "item must not be null");
        return decide(item, headSha, lastInstances.lastInstanceOf(
            item.id() == null ? 0L : item.id()));
    }

    /**
     * Decides the rerun scope with the last instance already loaded (the application service
     * needs it anyway to reuse the built image, so passing it keeps both decisions aligned).
     *
     * @param lastInstance the item's most recent pipeline instance, or {@code null}
     */
    public RerunPlan decide(ReleaseItem item, String headSha, PipelineInstance lastInstance) {
        Objects.requireNonNull(item, "item must not be null");
        String base = trimToNull(item.baseCommit());
        String head = trimToNull(headSha);

        if (head != null && head.equals(base)) {
            // Exactly the commit the item was built from: reuse the image and every check.
            return resumeFromNode(lastInstance);
        }
        if (head == null) {
            // No observed head: the platform cannot prove the commit is unchanged.
            return fullRerun(true);
        }
        if (item.modulePath() != null && base != null) {
            boolean changed = commitProbe.hasChangesUnder(
                item.unitId() == null ? 0L : item.unitId(), item.sourceBranch(),
                item.modulePath(), base);
            if (!changed) {
                // Another module of the same repository changed; this item's output did not.
                return resumeFromNode(lastInstance);
            }
            return fullRerun(false);
        }
        // No module path (or no baseline to filter from): whole-repository comparison, which
        // cannot attribute a change to this item. Conservative full rerun, flagged degraded.
        return fullRerun(true);
    }

    private static RerunPlan resumeFromNode(PipelineInstance lastInstance) {
        return new RerunPlan(false, resumeNodeOf(lastInstance), false, false);
    }

    private static RerunPlan fullRerun(boolean degraded) {
        return new RerunPlan(true, NodeType.BUILD, true, degraded);
    }

    /** The first failed/incomplete node of the last instance, or the production fallback. */
    private static NodeType resumeNodeOf(PipelineInstance last) {
        if (last == null) {
            // No previous execution: only a build can produce the image the next steps need.
            return NodeType.BUILD;
        }
        List<PipelineNode> nodes = last.nodes().stream()
            .sorted(Comparator.comparingInt(PipelineNode::seq))
            .toList();
        if (nodes.isEmpty()) {
            return NodeType.BUILD;
        }
        NodeType firstIncomplete = null;
        boolean anySucceeded = false;
        for (PipelineNode node : nodes) {
            if (node.status() == NodeStatus.FAILED) {
                return node.nodeType();
            }
            if (node.status() == NodeStatus.SUCCESS) {
                anySucceeded = true;
            }
            if (firstIncomplete == null
                    && node.status() != NodeStatus.SUCCESS
                    && node.status() != NodeStatus.SKIPPED) {
                firstIncomplete = node.nodeType();
            }
        }
        if (firstIncomplete != null) {
            return firstIncomplete;
        }
        // Every node succeeded (production is the only step left) or there is nothing to resume.
        return anySucceeded ? NodeType.PROD_DEPLOY : NodeType.BUILD;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Where the last execution of an item can be read. Kept as a narrow functional seam (rather
     * than a full repository dependency) so the decision stays a pure unit under test.
     */
    @FunctionalInterface
    public interface LastInstanceLookup {

        /** The most recent pipeline instance of {@code itemId}, or {@code null}. */
        PipelineInstance lastInstanceOf(long itemId);
    }
}
