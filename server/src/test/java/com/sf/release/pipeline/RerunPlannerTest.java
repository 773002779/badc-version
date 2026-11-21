package com.sf.release.pipeline;

import com.sf.release.domain.pipeline.ExecType;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.RerunPlan;
import com.sf.release.domain.pipeline.RerunPlanner;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.GateStatus;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commit-based rerun decision (tech design §5.3, requirements §4.5): an unchanged commit
 * resumes from the failed node and keeps the test pass, a changed commit is a full rerun that
 * revokes it, and a change confined to other modules of a shared repository is not a change for
 * this item.
 *
 * <p>Pure domain test: the CODING git probe and the last instance are stubs, so nothing here
 * talks to the network.
 */
class RerunPlannerTest {

    private static final String BASE = "a".repeat(40);
    private static final String OTHER = "b".repeat(40);
    private static final String MODULE = "outbound-os";
    private static final String BRANCH = "release/202611";
    private static final long UNIT_ID = 20L;

    private final StubGitCommitProbe probe = new StubGitCommitProbe();

    @Test
    void same_commit_resumes_from_the_failed_test_deploy_node_and_keeps_test_pass() {
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.SKIPPED, NodeStatus.SKIPPED));

        RerunPlan plan = planner.decide(item(BASE, MODULE), BASE);

        assertThat(plan.full()).isFalse();
        assertThat(plan.fromNode()).isEqualTo(NodeType.TEST_DEPLOY);
        assertThat(plan.revokeTestPass()).isFalse();
        assertThat(plan.degraded()).isFalse();
        assertThat(probe.changeCalls).isZero();
    }

    @Test
    void same_commit_after_every_node_succeeded_resumes_from_prod_deploy() {
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SUCCESS));

        RerunPlan plan = planner.decide(item(BASE, MODULE), BASE);

        assertThat(plan.full()).isFalse();
        assertThat(plan.fromNode()).isEqualTo(NodeType.PROD_DEPLOY);
        assertThat(plan.revokeTestPass()).isFalse();
    }

    @Test
    void first_incomplete_node_is_the_resume_point_when_no_node_failed() {
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.RUNNING, NodeStatus.PENDING));

        RerunPlan plan = planner.decide(item(BASE, MODULE), BASE);

        assertThat(plan.full()).isFalse();
        assertThat(plan.fromNode()).isEqualTo(NodeType.VERIFY);
    }

    @Test
    void changed_commit_triggers_full_rerun_from_build_and_revokes_test_pass() {
        probe.changesUnder = true;
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.SKIPPED, NodeStatus.SKIPPED));

        RerunPlan plan = planner.decide(item(BASE, MODULE), OTHER);

        assertThat(plan.full()).isTrue();
        assertThat(plan.fromNode()).isEqualTo(NodeType.BUILD);
        assertThat(plan.revokeTestPass()).isTrue();
        assertThat(plan.degraded()).isFalse();
        assertThat(probe.changeCalls).isEqualTo(1);
        assertThat(probe.lastUnitId).isEqualTo(UNIT_ID);
        assertThat(probe.lastBranch).isEqualTo(BRANCH);
        assertThat(probe.lastModulePath).isEqualTo(MODULE);
        assertThat(probe.lastBaseSha).isEqualTo(BASE);
    }

    @Test
    void commit_touching_only_other_modules_of_a_shared_repo_is_not_a_change() {
        probe.changesUnder = false;
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.SKIPPED, NodeStatus.SKIPPED));

        RerunPlan plan = planner.decide(item(BASE, MODULE), OTHER);

        assertThat(plan.full()).isFalse();
        assertThat(plan.fromNode()).isEqualTo(NodeType.TEST_DEPLOY);
        assertThat(plan.revokeTestPass()).isFalse();
        assertThat(probe.changeCalls)
            .as("the module path filter decides, the raw HEAD difference must not").isEqualTo(1);
    }

    @Test
    void missing_module_path_degrades_to_whole_repo_comparison() {
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.SKIPPED, NodeStatus.SKIPPED));

        RerunPlan plan = planner.decide(item(BASE, null), OTHER);

        assertThat(plan.full()).isTrue();
        assertThat(plan.revokeTestPass()).isTrue();
        assertThat(plan.degraded()).isTrue();
        assertThat(probe.changeCalls)
            .as("there is no module path to filter with, so the probe is not asked")
            .isZero();
    }

    @Test
    void identical_commit_is_not_degraded_even_without_a_module_path() {
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SUCCESS));

        RerunPlan plan = planner.decide(item(BASE, null), BASE);

        assertThat(plan.full()).isFalse();
        assertThat(plan.degraded())
            .as("no comparison was needed, so no degradation is reported").isFalse();
    }

    @Test
    void missing_base_commit_is_a_conservative_full_rerun_without_a_filtered_comparison() {
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.PENDING));

        RerunPlan plan = planner.decide(item(null, MODULE), OTHER);

        assertThat(plan.full()).isTrue();
        assertThat(plan.revokeTestPass()).isTrue();
        assertThat(plan.degraded()).isTrue();
        assertThat(probe.changeCalls).isZero();
    }

    @Test
    void commit_comparison_ignores_sha_case() {
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.SKIPPED, NodeStatus.SKIPPED));

        RerunPlan plan = planner.decide(item("A".repeat(40), MODULE), BASE);

        assertThat(plan.full()).isFalse();
        assertThat(plan.fromNode()).isEqualTo(NodeType.TEST_DEPLOY);
        assertThat(plan.revokeTestPass()).isFalse();
        assertThat(probe.changeCalls).isZero();
    }

    @Test
    void blank_module_path_is_treated_as_absent_and_degrades() {
        RerunPlanner planner = plannerFor(nodes(
            NodeStatus.SUCCESS, NodeStatus.FAILED, NodeStatus.SKIPPED, NodeStatus.SKIPPED));

        RerunPlan plan = planner.decide(item(BASE, "   "), OTHER);

        assertThat(plan.full()).isTrue();
        assertThat(plan.degraded()).isTrue();
        assertThat(probe.changeCalls).isZero();
    }

    @Test
    void an_item_without_a_previous_execution_resumes_at_build() {
        RerunPlanner planner = new RerunPlanner(probe, itemId -> null);

        RerunPlan plan = planner.decide(item(BASE, MODULE), BASE);

        assertThat(plan.full()).isFalse();
        assertThat(plan.fromNode()).isEqualTo(NodeType.BUILD);
        assertThat(plan.revokeTestPass()).isFalse();
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private RerunPlanner plannerFor(PipelineInstance lastInstance) {
        return new RerunPlanner(probe, itemId -> lastInstance);
    }

    private static ReleaseItem item(String baseCommit, String modulePath) {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        return new ReleaseItem(100L, 10L, "CRID-T15", UnitType.UNIT, UNIT_ID, "P-BUILD-T15",
            BRANCH, modulePath, "重新发布判定", LocalDate.of(2026, 11, 25), ItemStatus.FAILED,
            GateStatus.TEST_PENDING, baseCommit, null, null, 0, now, now);
    }

    private static PipelineInstance nodes(NodeStatus... statuses) {
        List<PipelineNode> nodes = new ArrayList<>(statuses.length);
        NodeType[] types = NodeType.values();
        for (int i = 0; i < statuses.length; i++) {
            nodes.add(new PipelineNode((long) i + 1, 1L, types[i], i, statuses[i], null, null,
                List.of()));
        }
        return new PipelineInstance(1L, 10L, "PL-T15-PLAN", ExecType.ONLINE,
            TriggerType.INITIAL, NodeType.BUILD, InstanceStatus.FAILED, null, null, null, null,
            Instant.ofEpochSecond(1_700_000_000L), null, nodes);
    }

    private static final class StubGitCommitProbe implements GitCommitProbe {
        private boolean changesUnder;
        private int changeCalls;
        private long lastUnitId;
        private String lastBranch;
        private String lastModulePath;
        private String lastBaseSha;

        @Override
        public String headSha(long unitId, String branch) {
            return null;
        }

        @Override
        public boolean hasChangesUnder(long unitId, String branch, String modulePath,
                                       String baseSha) {
            changeCalls++;
            lastUnitId = unitId;
            lastBranch = branch;
            lastModulePath = modulePath;
            lastBaseSha = baseSha;
            return changesUnder;
        }
    }
}
