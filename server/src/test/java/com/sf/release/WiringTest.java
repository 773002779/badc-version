package com.sf.release;

import com.sf.release.application.VerifyNodeHandler;
import com.sf.release.application.event.PipelineEventListener;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.handler.BuildNodeHandler;
import com.sf.release.domain.pipeline.handler.TestDeployNodeHandler;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.infra.engine.CodingEngine;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.lock.InMemoryDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring carried over from Tasks 10-13: the CODING adapter, its registry, both node handlers,
 * the orchestrator and the item listener are Spring beans, the app boots with no Redis and no
 * CODING tenant, and the adapter performs no network I/O at startup (only JSONPath validation).
 */
@SpringBootTest(properties = "release.wiring-probe=fresh-context")
@ActiveProfiles("test")
class WiringTest {

    @Autowired ApplicationContext context;
    @Autowired EngineRegistry engines;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired DistributedLock distributedLock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void noCodingTenant() {
        jdbc.update("DELETE FROM coding_conn");
    }

    @Test
    void the_engine_and_its_registry_are_beans_and_resolve_coding() {
        CicdEngine engine = engines.require("CODING");

        assertThat(engine).isInstanceOf(CodingEngine.class);
        assertThat(context.getBean(CodingEngine.class)).isSameAs(engine);
        assertThat(engine.name()).isEqualTo("CODING");
    }

    @Test
    void both_node_handlers_are_beans_and_registered_at_startup() {
        // This test class has its own context (the wiring-probe property above), so the
        // registry singleton it sees has not been replaced by another test class's fakes.
        assertThat(context.getBeansOfType(NodeHandler.class).values())
            .anyMatch(BuildNodeHandler.class::isInstance)
            .anyMatch(TestDeployNodeHandler.class::isInstance);

        assertThat(handlers.handler(NodeType.BUILD)).isInstanceOf(BuildNodeHandler.class);
        assertThat(handlers.handler(NodeType.TEST_DEPLOY)).isInstanceOf(TestDeployNodeHandler.class);
        assertThat(handlers.handler(NodeType.VERIFY))
            .as("P2 adds the verify handler; production stays P3")
            .isInstanceOf(VerifyNodeHandler.class);
        assertThat(handlers.handler(NodeType.PROD_DEPLOY)).isNull();
    }

    @Test
    void the_item_listener_is_a_bean() {
        assertThat(context.getBeansOfType(PipelineEventListener.class)).hasSize(1);
    }

    @Test
    void the_application_boots_without_redis() {
        assertThat(distributedLock).isInstanceOf(InMemoryDistributedLock.class);
        assertThat(context.getBeanNamesForType(RedissonClient.class)).isEmpty();
    }

    @Test
    void the_engine_boots_without_a_coding_tenant_and_does_no_eager_io() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coding_conn", Integer.class))
            .as("no CODING tenant is configured")
            .isZero();

        assertThatThrownBy(() -> engines.require("CODING").query("absent-job", JobKind.BUILD))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("连接未配置");
    }
}