package com.sf.release.pipeline;

import com.sf.release.application.ItemTransitions;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.port.ItemFailureRepairer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * I2: a lost {@code row_version} CAS on a 变更项 transition must not be dropped. The listener
 * records it in {@code pipeline_item_failure} (so the 30s reconcile replays it) while the
 * pipeline transaction keeps committing.
 *
 * <p>The transition itself is replaced by a stub returning {@code CAS_LOST}: forcing a real
 * concurrent write between the listener's read and its update is not deterministic, and the
 * behaviour under test is precisely what the listener does with that outcome.
 */
@SpringBootTest(properties = "release.item-failure-cas-probe=fresh-context")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ItemTransitionFailureTest {

    private static final long ORDER_ID = 987_654L;
    private static final long ITEM_ID = 123_456L;

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired ItemFailureRepairer failureRepairer;
    @Autowired JdbcTemplate jdbc;
    @MockBean ItemTransitions transitions;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pipeline_item_failure");
        jdbc.update("DELETE FROM release_order WHERE id = ?", ORDER_ID);
        jdbc.update("INSERT INTO release_order (id, publish_no, item_id, status, row_version,"
            + " created_at) VALUES (?, ?, ?, 'INIT', 0, NOW(3))",
            ORDER_ID, "PO-CAS-LOST", ITEM_ID);
    }

    @Test
    void a_lost_cas_is_persisted_for_the_reconcile_drain_and_then_resolved(CapturedOutput output) {
        when(transitions.apply(anyLong(), any()))
            .thenReturn(ItemTransitions.Outcome.CAS_LOST);

        eventPublisher.publishEvent(new NodeCompletedEvent(ORDER_ID, ORDER_ID, NodeType.BUILD,
            NodeStatus.SUCCESS, null, Instant.now()));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM pipeline_item_failure");
        assertThat(((Number) row.get("instance_id")).longValue()).isEqualTo(ORDER_ID);
        assertThat(((Number) row.get("item_id")).longValue()).isEqualTo(ITEM_ID);
        assertThat(row.get("event_name")).isEqualTo("BUILD_OK");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat(row.get("resolved_at")).isNull();
        assertThat(String.valueOf(row.get("error_message"))).contains("CAS lost");
        assertThat(output.getAll())
            .as("the dropped transition is visible in the log")
            .contains("CAS lost")
            .contains("itemId=" + ITEM_ID);

        // The 30s drain replays it: a transition that now applies resolves the row.
        when(transitions.apply(anyLong(), any())).thenReturn(ItemTransitions.Outcome.APPLIED);
        assertThat(failureRepairer.retryUnresolved(10)).isEqualTo(1);

        Timestamp resolvedAt = jdbc.queryForObject(
            "SELECT resolved_at FROM pipeline_item_failure", Timestamp.class);
        assertThat(resolvedAt).as("the repair marks the row resolved").isNotNull();
    }
}