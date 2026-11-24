package com.sf.release.daily;

import com.sf.release.domain.pipeline.ExecType;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineFactory;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.resource.EnvKind;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.GateStatus;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The daily pipeline shape (P5-A design §3, §7 item 1): {@code BUILD → DAILY_DEPLOY}, the three
 * build tasks, one {@code DEPLOY} per target DE, no release order and the daily identity.
 *
 * <p>Pure domain: no Spring, no database. The target selection (DAILY with the TEST fallback) is
 * the factory's, so this test also pins C24's "reuse the test deployment target" rule.
 */
class DailyPipelineFactoryTest {

    private static final long ITEM_ID = 41L;
    private static final long UNIT_ID = 7L;
    private static final long DAILY_DE_ID = 101L;
    private static final long TEST_DE_ID = 202L;
    private static final Instant NOW = Instant.parse("2026-11-25T01:00:00Z");

    @Test
    void daily_pipeline_has_a_build_node_and_one_daily_deploy_node() {
        PipelineInstance instance = PipelineFactory.createDaily(
            item(UnitType.UNIT), UNIT_ID, List.of(dailyDe(DAILY_DE_ID, 3)), "dev-01", NOW);

        assertThat(instance.nodes()).hasSize(2);
        PipelineNode build = instance.node(NodeType.BUILD);
        PipelineNode deploy = instance.node(NodeType.DAILY_DEPLOY);
        assertThat(build).isNotNull();
        assertThat(deploy).isNotNull();
        assertThat(build.seq()).isZero();
        assertThat(deploy.seq()).isEqualTo(1);

        assertThat(build.tasks()).extracting(NodeTask::taskType)
            .containsExactly(TaskType.CODE_CHECK, TaskType.PKG_BUILD, TaskType.IMG_BUILD);
        assertThat(build.tasks()).extracting(NodeTask::nodeType)
            .containsOnly(NodeType.BUILD);
        assertThat(build.tasks()).extracting(NodeTask::status)
            .containsOnly(NodeTaskStatus.PENDING);
        assertThat(build.tasks()).allSatisfy(task -> assertThat(task.batchNo()).isZero());
    }

    @Test
    void every_daily_de_becomes_one_deploy_task_carrying_its_batch_and_target() {
        // Two DAILY DEs with different batches: the tasks are ordered by batch then id.
        PipelineInstance instance = PipelineFactory.createDaily(
            item(UnitType.UNIT), UNIT_ID,
            List.of(dailyDe(DAILY_DE_ID, 3), dailyDe(TEST_DE_ID, 1)), "dev-01", NOW);

        PipelineNode deploy = instance.node(NodeType.DAILY_DEPLOY);
        assertThat(deploy.tasks()).hasSize(2);
        NodeTask first = deploy.tasks().get(0);
        assertThat(first.nodeType()).isEqualTo(NodeType.DAILY_DEPLOY);
        assertThat(first.taskType()).isEqualTo(TaskType.DEPLOY);
        assertThat(first.batchNo()).as("the DE batch is display only for a daily deploy")
            .isEqualTo(1);
        assertThat(first.targetId()).isEqualTo(TEST_DE_ID);
        assertThat(first.status()).isEqualTo(NodeTaskStatus.PENDING);

        NodeTask second = deploy.tasks().get(1);
        assertThat(second.targetId()).isEqualTo(DAILY_DE_ID);
        assertThat(second.batchNo()).isEqualTo(3);
    }

    @Test
    void instance_is_daily_with_order_zero_and_its_own_item_unit_and_requester() {
        PipelineInstance instance = PipelineFactory.createDaily(
            item(UnitType.UNIT), UNIT_ID, List.of(dailyDe(DAILY_DE_ID, 0)), "01388007", NOW);

        assertThat(instance.orderId()).as("a daily release creates no release order").isZero();
        assertThat(instance.execType()).isEqualTo(ExecType.DAILY);
        assertThat(instance.triggerType()).isEqualTo(TriggerType.INITIAL);
        assertThat(instance.currentNode()).isEqualTo(NodeType.BUILD);
        assertThat(instance.status()).isEqualTo(InstanceStatus.PENDING);
        assertThat(instance.itemId()).isEqualTo(ITEM_ID);
        assertThat(instance.unitId()).isEqualTo(UNIT_ID);
        assertThat(instance.requestedBy()).isEqualTo("01388007");
        assertThat(instance.pipelineNo()).startsWith("PL-");
        assertThat(instance.createdAt()).isEqualTo(NOW);
        assertThat(instance.imageDigest()).isNull();
        assertThat(instance.commitSha()).isNull();
    }

    @Test
    void test_des_are_the_fallback_when_the_unit_has_no_daily_de() {
        PipelineInstance instance = PipelineFactory.createDaily(
            item(UnitType.UNIT), UNIT_ID,
            List.of(testDe(TEST_DE_ID, 0), prodDe(999L, 1)), "dev-01", NOW);

        PipelineNode deploy = instance.node(NodeType.DAILY_DEPLOY);
        assertThat(deploy.tasks()).as("only TEST DEs are the C24 fallback, never a PROD DE")
            .hasSize(1);
        assertThat(deploy.tasks().get(0).targetId()).isEqualTo(TEST_DE_ID);
    }

    @Test
    void daily_des_win_over_test_des_and_keep_the_batch_then_id_order() {
        PipelineInstance instance = PipelineFactory.createDaily(
            item(UnitType.UNIT), UNIT_ID,
            List.of(testDe(TEST_DE_ID, 0), dailyDe(102L, 5), dailyDe(101L, 2)),
            "dev-01", NOW);

        assertThat(instance.node(NodeType.DAILY_DEPLOY).tasks())
            .extracting(NodeTask::targetId).containsExactly(101L, 102L);
    }

    @Test
    void no_daily_and_no_test_de_is_a_programming_error_not_a_half_built_pipeline() {
        assertThatThrownBy(() -> PipelineFactory.createDaily(
            item(UnitType.UNIT), UNIT_ID, List.of(prodDe(999L, 1)), "dev-01", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DAILY or TEST");
    }

    @Test
    void a_library_item_never_gets_a_daily_pipeline() {
        assertThatThrownBy(() -> PipelineFactory.createDaily(
            item(UnitType.LIB), UNIT_ID, List.of(dailyDe(DAILY_DE_ID, 0)), "dev-01", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UNIT");
    }

    @Test
    void the_online_pipeline_shape_is_unchanged() {
        // Regression for the "do not change ONLINE semantics" rule: the new enum values and the
        // appended daily identity must not leak into the online factory.
        PipelineInstance online = PipelineFactory.createOnline(
            order(), item(UnitType.UNIT),
            List.of(testDe(TEST_DE_ID, 0), prodDe(999L, 1)));

        assertThat(online.nodes()).extracting(PipelineNode::nodeType)
            .containsExactly(NodeType.BUILD, NodeType.TEST_DEPLOY, NodeType.VERIFY,
                NodeType.PROD_DEPLOY);
        assertThat(online.execType()).isEqualTo(ExecType.ONLINE);
        assertThat(online.itemId()).isNull();
        assertThat(online.unitId()).isNull();
        assertThat(online.requestedBy()).isNull();
    }

    // ------------------------------------------------------------------ fixtures

    private static ReleaseItem item(UnitType type) {
        return new ReleaseItem(ITEM_ID, 12L, "CRID-DAILY-1", type, UNIT_ID, "P-BUILD-DAILY",
            "release/202611", "outbound-os/quota", "日常发布", LocalDate.of(2026, 11, 25),
            ItemStatus.PENDING, GateStatus.CHECK_RUNNING, null, null, null, 0, NOW, NOW);
    }

    private static com.sf.release.domain.pipeline.ReleaseOrder order() {
        return new com.sf.release.domain.pipeline.ReleaseOrder(9L, "PO-20261125-ABC123", ITEM_ID,
            null, null, null, null, "INIT", 0, NOW);
    }

    private static EnvDe dailyDe(long id, int batchNo) {
        return de(id, EnvKind.DAILY, "quota-daily-" + id, batchNo);
    }

    private static EnvDe testDe(long id, int batchNo) {
        return de(id, EnvKind.TEST, "quota-sit-" + id, batchNo);
    }

    private static EnvDe prodDe(long id, int batchNo) {
        return de(id, EnvKind.PROD, "quota-prod-" + id, batchNo);
    }

    private static EnvDe de(long id, EnvKind kind, String name, int batchNo) {
        return new EnvDe(id, UNIT_ID, kind, null, name, null, null, batchNo, NOW, NOW);
    }
}