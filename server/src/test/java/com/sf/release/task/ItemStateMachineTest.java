package com.sf.release.task;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.resource.UnitType;
import com.sf.release.domain.task.GateStatus;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStateMachine;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The main state machine of a 变更项 (tech design §3.2, requirements §4.4).
 *
 * <p>The truth table is written out independently of {@code ItemStateMachine} in
 * {@link #EXPECTED} and every {@code (status, event)} pair is generated from it: a legal pair
 * must reach the documented target, every other pair must throw {@code STATE_NOT_ALLOWED}.
 * That catches an edge the implementation invents as well as a missing one.
 */
class ItemStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-21T02:00:00Z");

    @ParameterizedTest
    @EnumSource(ItemStatus.class)
    void every_status_has_defined_transitions(ItemStatus from) {
        // Never null: a closed-out state simply has no outgoing event.
        assertThat(ItemStateMachine.allowedEvents(from)).isNotNull();
    }

    @Test
    void pending_to_publish_to_testing() {
        assertThat(transit(ItemStatus.PENDING, ItemEvent.PUBLISH)).isEqualTo(ItemStatus.BUILDING);
        assertThat(transit(ItemStatus.BUILDING, ItemEvent.BUILD_OK)).isEqualTo(ItemStatus.TESTING);
    }

    @Test
    void invalid_transition_throws_with_current_status_payload() {
        BizException e = assertThrows(BizException.class,
            () -> transit(ItemStatus.DONE, ItemEvent.PUBLISH));
        assertThat(e.getError()).isEqualTo(BizError.STATE_NOT_ALLOWED);
        assertThat(e.getPayload()).asInstanceOf(MAP).containsEntry("currentStatus", "DONE");
    }

    @Test
    void test_revoke_moves_testing_back_to_gate_failed() {
        assertThat(transit(ItemStatus.TESTING, ItemEvent.TEST_REVOKE))
            .isEqualTo(ItemStatus.GATE_FAILED);
    }

    @Test
    void terminal_states_have_no_outgoing_events() {
        assertThat(ItemStateMachine.allowedEvents(ItemStatus.CLOSED)).isEmpty();
        assertThat(ItemStateMachine.allowedEvents(ItemStatus.TERMINATED)).isEmpty();
        assertThat(ItemStateMachine.allowedEvents(ItemStatus.ROLLED_BACK)).isEmpty();
    }

    /**
     * Generated truth table: 15 statuses × every event. {@link #EXPECTED} is the only source
     * of truth in this test; the assertions are derived, not mirrored from the implementation.
     */
    @Test
    void truth_table_matches_every_status_event_pair() {
        for (ItemStatus from : ItemStatus.values()) {
            Set<ItemEvent> expectedEvents = EXPECTED.get(from).keySet();
            assertThat(ItemStateMachine.allowedEvents(from))
                .as("allowedEvents(%s)", from)
                .containsExactlyInAnyOrderElementsOf(expectedEvents);

            for (ItemEvent event : ItemEvent.values()) {
                ItemStatus expected = EXPECTED.get(from).get(event);
                if (expected == null) {
                    assertThatThrownBy(() -> transit(from, event))
                        .as("%s + %s must be illegal", from, event)
                        .isInstanceOf(BizException.class)
                        .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED);
                } else {
                    assertThat(transit(from, event))
                        .as("%s --%s--> %s", from, event, expected)
                        .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void gate_failure_lands_in_gate_failed_and_cannot_be_submitted() {
        assertThat(transit(ItemStatus.TESTING, ItemEvent.GATE_FAIL))
            .isEqualTo(ItemStatus.GATE_FAILED);
        // The design's second target for the old GATE_DECIDE cell: a failed gate can never
        // reach SUBMITTED, so it cannot be admitted to production.
        assertThatThrownBy(() -> transit(ItemStatus.GATE_FAILED, ItemEvent.SUBMIT))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED);
    }

    @Test
    void gate_pass_moves_testing_to_gate_pending() {
        assertThat(transit(ItemStatus.TESTING, ItemEvent.GATE_PASS))
            .isEqualTo(ItemStatus.GATE_PENDING);
    }

    @Test
    void test_deploy_ok_is_an_idempotent_callback_while_testing() {
        // The engine's test-deploy success callback may arrive late; rejecting it with 40001
        // would break the callback loop, so it acknowledges the state instead of moving it.
        assertThat(transit(ItemStatus.TESTING, ItemEvent.TEST_DEPLOY_OK))
            .isEqualTo(ItemStatus.TESTING);
    }

    @Test
    void full_rerun_rebuilds_from_building() {
        for (ItemStatus failed : List.of(ItemStatus.GATE_FAILED, ItemStatus.FAILED)) {
            assertThat(transit(failed, ItemEvent.RERUN_FULL)).isEqualTo(ItemStatus.BUILDING);
        }
        // BUILD_OK/BUILD_FAIL are only legal from BUILDING, which is exactly what a full rerun
        // needs after the commit changed (requirements §4.5).
        assertThat(transit(ItemStatus.BUILDING, ItemEvent.BUILD_OK)).isEqualTo(ItemStatus.TESTING);
    }

    @Test
    void gate_invalidation_revokes_admission_from_submitted_and_ready() {
        assertThat(transit(ItemStatus.SUBMITTED, ItemEvent.CHECK_INVALID))
            .isEqualTo(ItemStatus.GATE_FAILED);
        assertThat(transit(ItemStatus.READY, ItemEvent.CHECK_INVALID))
            .isEqualTo(ItemStatus.GATE_FAILED);
        assertThat(transit(ItemStatus.PENDING_APPROVAL, ItemEvent.GATE_INVALID))
            .isEqualTo(ItemStatus.GATE_FAILED);
        assertThat(transit(ItemStatus.READY, ItemEvent.FREEZE_HIT))
            .isEqualTo(ItemStatus.PENDING_APPROVAL);
    }

    @Test
    void testing_check_invalid_fails_the_gate() {
        // Environment-level checks run after the test deployment; an invalid result while
        // testing must revoke the gate instead of leaving the item in TESTING.
        assertThat(transit(ItemStatus.TESTING, ItemEvent.CHECK_INVALID))
            .isEqualTo(ItemStatus.GATE_FAILED);
    }

    @Test
    void any_non_terminal_state_can_be_closed_or_exited() {
        List<ItemStatus> inFlightAndSideEnds = List.of(
            ItemStatus.PENDING, ItemStatus.BUILDING, ItemStatus.TESTING, ItemStatus.GATE_PENDING,
            ItemStatus.SUBMITTED, ItemStatus.PENDING_APPROVAL, ItemStatus.READY,
            ItemStatus.PROD_DEPLOYING, ItemStatus.GATE_FAILED, ItemStatus.FAILED,
            ItemStatus.REJECTED);
        for (ItemStatus status : inFlightAndSideEnds) {
            assertThat(transit(status, ItemEvent.CLOSE)).isEqualTo(ItemStatus.CLOSED);
            assertThat(transit(status, ItemEvent.EXIT)).isEqualTo(ItemStatus.TERMINATED);
        }

        // Success and closed-out ends only accept their own documented event (if any).
        for (ItemStatus status : List.of(ItemStatus.DONE, ItemStatus.CLOSED,
            ItemStatus.TERMINATED, ItemStatus.ROLLED_BACK)) {
            for (ItemEvent event : List.of(ItemEvent.CLOSE, ItemEvent.EXIT)) {
                assertThatThrownBy(() -> transit(status, event))
                    .as("%s must not accept %s", status, event)
                    .isInstanceOf(BizException.class)
                    .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED);
            }
        }
    }

    @Test
    void done_only_accepts_rollback() {
        assertThat(ItemStateMachine.allowedEvents(ItemStatus.DONE))
            .containsExactly(ItemEvent.ROLLBACK);
    }

    @Test
    void events_outside_the_map_are_rejected() {
        for (ItemEvent event : new ItemEvent[] {ItemEvent.REJECT, ItemEvent.PROD_OK,
            ItemEvent.SUBMIT, ItemEvent.EXECUTE, ItemEvent.ROLLBACK}) {
            assertThatThrownBy(() -> transit(ItemStatus.PENDING, event))
                .as("PENDING must not accept %s", event)
                .isInstanceOf(BizException.class)
                .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED);
        }
    }

    @Test
    void the_online_main_path_reaches_done() {
        ItemStatus status = ItemStatus.PENDING;
        ItemEvent[] path = {ItemEvent.PUBLISH, ItemEvent.BUILD_OK, ItemEvent.GATE_PASS,
            ItemEvent.SUBMIT, ItemEvent.NO_APPROVAL, ItemEvent.EXECUTE, ItemEvent.PROD_OK};
        ItemStatus[] expected = {ItemStatus.BUILDING, ItemStatus.TESTING, ItemStatus.GATE_PENDING,
            ItemStatus.SUBMITTED, ItemStatus.READY, ItemStatus.PROD_DEPLOYING, ItemStatus.DONE};
        for (int i = 0; i < path.length; i++) {
            status = transit(status, path[i]);
            assertThat(status).isEqualTo(expected[i]);
        }
        assertThat(transit(status, ItemEvent.ROLLBACK)).isEqualTo(ItemStatus.ROLLED_BACK);
    }

    // --- independent truth table (tech design §3.2 + review rulings) -------------------------

    private static final Map<ItemStatus, Map<ItemEvent, ItemStatus>> EXPECTED = expected();

    private static Map<ItemStatus, Map<ItemEvent, ItemStatus>> expected() {
        Map<ItemStatus, Map<ItemEvent, ItemStatus>> matrix = new EnumMap<>(ItemStatus.class);
        for (ItemStatus status : ItemStatus.values()) {
            matrix.put(status, new EnumMap<>(ItemEvent.class));
        }
        legal(matrix, ItemStatus.PENDING, ItemEvent.PUBLISH, ItemStatus.BUILDING);
        legal(matrix, ItemStatus.BUILDING, ItemEvent.BUILD_OK, ItemStatus.TESTING);
        legal(matrix, ItemStatus.BUILDING, ItemEvent.BUILD_FAIL, ItemStatus.FAILED);
        legal(matrix, ItemStatus.TESTING, ItemEvent.GATE_PASS, ItemStatus.GATE_PENDING);
        legal(matrix, ItemStatus.TESTING, ItemEvent.GATE_FAIL, ItemStatus.GATE_FAILED);
        legal(matrix, ItemStatus.TESTING, ItemEvent.TEST_DEPLOY_OK, ItemStatus.TESTING);
        legal(matrix, ItemStatus.TESTING, ItemEvent.TEST_DEPLOY_FAIL, ItemStatus.FAILED);
        legal(matrix, ItemStatus.TESTING, ItemEvent.TEST_REVOKE, ItemStatus.GATE_FAILED);
        legal(matrix, ItemStatus.TESTING, ItemEvent.CHECK_INVALID, ItemStatus.GATE_FAILED);
        legal(matrix, ItemStatus.GATE_PENDING, ItemEvent.SUBMIT, ItemStatus.SUBMITTED);
        legal(matrix, ItemStatus.GATE_PENDING, ItemEvent.CHECK_INVALID, ItemStatus.GATE_FAILED);
        legal(matrix, ItemStatus.GATE_PENDING, ItemEvent.TEST_REVOKE, ItemStatus.GATE_FAILED);
        legal(matrix, ItemStatus.SUBMITTED, ItemEvent.NEED_APPROVAL,
            ItemStatus.PENDING_APPROVAL);
        legal(matrix, ItemStatus.SUBMITTED, ItemEvent.FREEZE_HIT, ItemStatus.PENDING_APPROVAL);
        legal(matrix, ItemStatus.SUBMITTED, ItemEvent.NO_APPROVAL, ItemStatus.READY);
        legal(matrix, ItemStatus.SUBMITTED, ItemEvent.CHECK_INVALID, ItemStatus.GATE_FAILED);
        legal(matrix, ItemStatus.PENDING_APPROVAL, ItemEvent.APPROVE_ALL, ItemStatus.READY);
        legal(matrix, ItemStatus.PENDING_APPROVAL, ItemEvent.REJECT, ItemStatus.REJECTED);
        legal(matrix, ItemStatus.PENDING_APPROVAL, ItemEvent.GATE_INVALID,
            ItemStatus.GATE_FAILED);
        legal(matrix, ItemStatus.READY, ItemEvent.EXECUTE, ItemStatus.PROD_DEPLOYING);
        legal(matrix, ItemStatus.READY, ItemEvent.FREEZE_HIT, ItemStatus.PENDING_APPROVAL);
        legal(matrix, ItemStatus.READY, ItemEvent.CHECK_INVALID, ItemStatus.GATE_FAILED);
        legal(matrix, ItemStatus.PROD_DEPLOYING, ItemEvent.PROD_OK, ItemStatus.DONE);
        legal(matrix, ItemStatus.PROD_DEPLOYING, ItemEvent.PROD_FAIL, ItemStatus.FAILED);
        legal(matrix, ItemStatus.GATE_FAILED, ItemEvent.RERUN, ItemStatus.TESTING);
        legal(matrix, ItemStatus.GATE_FAILED, ItemEvent.RERUN_FULL, ItemStatus.BUILDING);
        legal(matrix, ItemStatus.FAILED, ItemEvent.RERUN, ItemStatus.TESTING);
        legal(matrix, ItemStatus.FAILED, ItemEvent.RERUN_FULL, ItemStatus.BUILDING);
        legal(matrix, ItemStatus.REJECTED, ItemEvent.RESUBMIT, ItemStatus.SUBMITTED);
        legal(matrix, ItemStatus.DONE, ItemEvent.ROLLBACK, ItemStatus.ROLLED_BACK);
        for (ItemStatus status : List.of(ItemStatus.PENDING, ItemStatus.BUILDING,
            ItemStatus.TESTING, ItemStatus.GATE_PENDING, ItemStatus.SUBMITTED,
            ItemStatus.PENDING_APPROVAL, ItemStatus.READY, ItemStatus.PROD_DEPLOYING,
            ItemStatus.GATE_FAILED, ItemStatus.FAILED, ItemStatus.REJECTED)) {
            legal(matrix, status, ItemEvent.CLOSE, ItemStatus.CLOSED);
            legal(matrix, status, ItemEvent.EXIT, ItemStatus.TERMINATED);
        }
        return matrix;
    }

    private static void legal(Map<ItemStatus, Map<ItemEvent, ItemStatus>> matrix,
                              ItemStatus from, ItemEvent event, ItemStatus to) {
        matrix.get(from).put(event, to);
    }

    private static ItemStatus transit(ItemStatus from, ItemEvent event) {
        return ItemStateMachine.transit(item(from), event);
    }

    private static ReleaseItem item(ItemStatus status) {
        return new ReleaseItem(1L, 2L, "CR-20261121-000001", UnitType.UNIT, 3L, "P-BUILD-1",
            "release/202611", null, "v1", LocalDate.of(2026, 11, 25), status,
            GateStatus.TEST_PENDING, null, null, null, 0, NOW, NOW);
    }
}