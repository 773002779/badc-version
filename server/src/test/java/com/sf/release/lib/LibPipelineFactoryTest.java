package com.sf.release.lib;

import com.sf.release.domain.pipeline.ExecType;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineFactory;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineNode;
import com.sf.release.domain.pipeline.ReleaseOrder;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.TriggerType;
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
 * The pre-generated library graph (P6-A design §3, test §5 item 2): three nodes, a two-task BUILD
 * chain and no image build, test deploy, verification or production deploy. Pure domain test: no
 * Spring, no database.
 */
class LibPipelineFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    @Test
    void create_lib_builds_three_nodes_with_a_two_task_build_chain() {
        PipelineInstance instance = PipelineFactory.createLib(order(), libItem(), NOW);

        assertThat(instance.execType()).isEqualTo(ExecType.LIB);
        assertThat(instance.triggerType()).isEqualTo(TriggerType.INITIAL);
        assertThat(instance.currentNode()).isEqualTo(NodeType.BUILD);
        assertThat(instance.status().name()).isEqualTo("PENDING");
        assertThat(instance.orderId()).isEqualTo(7L);
        assertThat(instance.createdAt()).isEqualTo(NOW);
        assertThat(instance.pipelineNo()).startsWith("PL-");

        List<PipelineNode> nodes = instance.nodes();
        assertThat(nodes).extracting(PipelineNode::nodeType)
            .containsExactly(NodeType.BUILD, NodeType.LIB_SECURITY, NodeType.LIB_PUBLISH);
        assertThat(nodes).extracting(PipelineNode::seq).containsExactly(0, 1, 2);
        assertThat(nodes).allSatisfy(node -> assertThat(node.status().name()).isEqualTo("PENDING"));

        assertThat(taskTypes(instance, NodeType.BUILD))
            .as("a library is packaged, never imaged")
            .containsExactly(TaskType.CODE_CHECK, TaskType.PKG_BUILD);

        PipelineNode security = instance.node(NodeType.LIB_SECURITY);
        assertThat(security.tasks())
            .as("the three checks live in gate_check, not in node_task")
            .isEmpty();

        assertThat(taskTypes(instance, NodeType.LIB_PUBLISH))
            .containsExactly(TaskType.LIB_PUBLISH);

        assertThat(nodes).extracting(PipelineNode::nodeType)
            .doesNotContain(NodeType.TEST_DEPLOY, NodeType.VERIFY, NodeType.PROD_DEPLOY,
                NodeType.DAILY_DEPLOY);
    }

    @Test
    void create_lib_rejects_a_deployment_unit_item() {
        ReleaseItem unitItem = new ReleaseItem(3L, 1L, "CR-1", UnitType.UNIT, 9L, "P-BUILD",
            "release/p6a", "quota-api", "1.2.3", LocalDate.of(2026, 11, 25), ItemStatus.PENDING,
            GateStatus.CHECK_RUNNING, null, null, null, 0, NOW, NOW);

        assertThatThrownBy(() -> PipelineFactory.createLib(order(), unitItem, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LIB item");
    }

    private static List<TaskType> taskTypes(PipelineInstance instance, NodeType nodeType) {
        return instance.node(nodeType).tasks().stream().map(task -> task.taskType()).toList();
    }

    private static ReleaseOrder order() {
        return new ReleaseOrder(7L, "PO-20261125-ABCDEF", 3L, null, null, null, null, "INIT", 0,
            NOW);
    }

    private static ReleaseItem libItem() {
        return new ReleaseItem(3L, 1L, "CR-2", UnitType.LIB, 9L, "P-BUILD-SDK",
            "release/p6a", null, "1.2.3", LocalDate.of(2026, 11, 25), ItemStatus.PENDING,
            GateStatus.CHECK_RUNNING, null, null, null, 0, NOW, NOW);
    }
}