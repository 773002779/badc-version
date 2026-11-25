package com.sf.release.lib;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.handler.BuildNodeHandler;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the deployment-unit path (P6-A rule "UNIT behaviour must not change"):
 * a UNIT publish still creates the four-node online graph, the build chain still has
 * {@code IMG_BUILD}, and a successful build still moves the item to {@code TESTING} — never to
 * the library states and never through a LIB node.
 */
@SpringBootTest
@ActiveProfiles("test")
class LibUnitRegressionTest {

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired NodeTaskCompletionService completionService;
    @Autowired JdbcTemplate jdbc;

    private LibStubEngine engine;
    private long unitId;
    private long itemId;
    private long instanceId;

    @BeforeEach
    void setUp() {
        LibFixtures.reset(jdbc);
        TestUserContext.allUnits(LibFixtures.OWNER);
        LibFixtures.givenUser(jdbc, LibFixtures.OWNER);
        long subsystemId = LibFixtures.givenSubsystem(jdbc, "SCC-P6A-UNIT-REG");
        long taskId = LibFixtures.givenTask(jdbc, subsystemId, "202611-P6A-REG");
        unitId = LibFixtures.givenUnit(jdbc, subsystemId, "quota-api", "UNIT", null, null, null);
        LibFixtures.givenBinding(jdbc, unitId, "BUILD", "P-BUILD-QUOTA");
        LibFixtures.givenBinding(jdbc, unitId, "TEST_DEPLOY", "P-TEST-QUOTA");
        LibFixtures.givenBinding(jdbc, unitId, "PROD_DEPLOY", "P-PROD-QUOTA");
        LibFixtures.givenDe(jdbc, unitId, "TEST", "quota-sit", 0);
        LibFixtures.givenDe(jdbc, unitId, "PROD", "quota-prod", 1);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, LibFixtures.BRANCH, "配额优化", LibFixtures.PLAN_DATE));

        engine = new LibStubEngine();
        handlers.clear();
        handlers.register(new BuildNodeHandler(engine, repository, transactionRunner,
            completionService));
        handlers.register(noop(NodeType.TEST_DEPLOY));
        handlers.register(noop(NodeType.VERIFY));
        handlers.register(noop(NodeType.PROD_DEPLOY));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
    }

    @Test
    void a_unit_publish_still_creates_four_nodes_and_a_build_success_still_means_testing() {
        PublishResult result = publishService.publish(itemId);
        assertThat(result.type())
            .as("the publish response now names its type, and a deployment unit is UNIT")
            .isEqualTo("UNIT");

        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            result.pipelineNo());
        instanceId = id == null ? 0L : id;

        assertThat(jdbc.queryForObject("SELECT exec_type FROM pipeline_instance WHERE id = ?",
            String.class, instanceId)).isEqualTo("ONLINE");
        assertThat(jdbc.queryForList(
                "SELECT node_type FROM pipeline_node WHERE instance_id = ? ORDER BY seq_no",
                String.class, instanceId))
            .containsExactly("BUILD", "TEST_DEPLOY", "VERIFY", "PROD_DEPLOY");
        assertThat(jdbc.queryForList(
                "SELECT task_type FROM node_task WHERE instance_id = ? AND node_type = 'BUILD'"
                    + " ORDER BY id", String.class, instanceId))
            .containsExactly("CODE_CHECK", "PKG_BUILD", "IMG_BUILD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gate_check", Integer.class))
            .as("a UNIT publish does not start any library check")
            .isZero();

        orchestrator.advance(instanceId);
        orchestrator.onTaskCompleted(taskId(TaskType.CODE_CHECK), NodeTaskStatus.SUCCESS, null,
            null);
        orchestrator.onTaskCompleted(taskId(TaskType.PKG_BUILD), NodeTaskStatus.SUCCESS, null,
            null);
        orchestrator.onTaskCompleted(taskId(TaskType.IMG_BUILD), NodeTaskStatus.SUCCESS,
            "sha256:unit", null);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = 'BUILD'",
            String.class, instanceId)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM release_item WHERE id = ?", String.class, itemId))
            .as("BUILD_OK→TESTING is the deployment-unit mapping and stays unchanged")
            .isEqualTo("TESTING");
        assertThat(jdbc.queryForObject(
            "SELECT current_node FROM pipeline_instance WHERE id = ?", String.class, instanceId))
            .isEqualTo("TEST_DEPLOY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gate_check", Integer.class))
            .isZero();
    }

    private long taskId(TaskType type) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'BUILD'"
                + " AND task_type = ?", Long.class, instanceId, type.name());
        return id == null ? 0L : id;
    }

    private static NodeHandler noop(NodeType type) {
        return new NodeHandler() {
            @Override
            public NodeType nodeType() {
                return type;
            }

            @Override
            public void start(PipelineInstance instance) {
                // The later deployment nodes are out of this regression's scope.
            }
        };
    }
}