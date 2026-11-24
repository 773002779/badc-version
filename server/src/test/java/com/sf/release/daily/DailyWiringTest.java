package com.sf.release.daily;

import com.sf.release.application.DailyReleaseService;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.handler.DailyDeployNodeHandler;
import com.sf.release.infra.repo.DailyQueryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production wiring of the daily slice: the handler is a bean and is registered for
 * {@code DAILY_DEPLOY}, the service is a bean, and the query mapper is scanned.
 *
 * <p>This class asks for its own context ({@code release.wiring-probe=daily}) because the handler
 * tests replace the registry singleton of the shared test context with their own stub handler; the
 * real wiring must be asserted in a context nothing else has mutated.
 */
@SpringBootTest(properties = "release.wiring-probe=daily")
@ActiveProfiles("test")
class DailyWiringTest {

    @Autowired ApplicationContext context;
    @Autowired NodeHandlerRegistry handlers;

    @Test
    void the_daily_deploy_handler_is_a_bean_and_is_registered() {
        assertThat(context.getBeansOfType(NodeHandler.class).values())
            .anyMatch(DailyDeployNodeHandler.class::isInstance);
        assertThat(handlers.handler(NodeType.DAILY_DEPLOY))
            .as("the orchestrator refuses to enter a node without a handler")
            .isInstanceOf(DailyDeployNodeHandler.class);
    }

    @Test
    void every_node_type_has_a_handler_after_p5a() {
        assertThat(handlers.nodeTypes())
            .containsExactlyInAnyOrder(NodeType.values())
            .contains(NodeType.DAILY_DEPLOY);
    }

    @Test
    void the_daily_service_and_query_mapper_are_beans() {
        assertThat(context.getBean(DailyReleaseService.class)).isNotNull();
        assertThat(context.getBean(DailyQueryMapper.class)).isNotNull();
    }
}