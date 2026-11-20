package com.sf.release.domain.task;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The main state machine of a 变更项 (tech design §3.2, requirements §4.4), defined
 * statically as {@code Map<Status, Map<Event, Status>>}. There is no state-specific logic:
 * every legal edge is a row of the map, and anything else throws
 * {@link IllegalTransitionException} (40001) with the current status in the payload.
 *
 * <p><strong>Rows and the two multi-target cells.</strong> The design table has two cells
 * with more than one target, both resolved here to a single deterministic target:
 * <ul>
 *   <li>{@code TESTING + RERUN}: the rerun planner decides the scope, the main state always
 *   re-enters {@code TESTING} (brief ruling); {@code GATE_FAILED/FAILED + RERUN} likewise.</li>
 *   <li>{@code TESTING + GATE_DECIDE}: the positive conclusion is {@code GATE_PENDING}; the
 *   negative conclusion is expressed by {@code TEST_REVOKE}/{@code CHECK_INVALID}, which the
 *   map accepts directly from {@code TESTING} as well (requirements §4.4:
 *   测试中 → 门禁未通过) and from {@code GATE_PENDING} as the design table specifies.</li>
 * </ul>
 *
 * <p><strong>Naming.</strong> The design's {@code FAIL} engine callback is
 * {@link ItemEvent#BUILD_FAIL} in the plan's shared type set. {@code TEST_DEPLOY_OK} is in
 * that set but has no row in the §3.2 matrix (the main state already is {@code TESTING} when
 * the test deployment succeeds), so it is not accepted by this machine yet.
 *
 * <p><strong>CLOSE / EXIT.</strong> The "any non-terminal" rows apply to every state except
 * the success end {@code DONE} and the closed-out ends {@code CLOSED}, {@code TERMINATED} and
 * {@code ROLLED_BACK}. {@code DONE} only accepts {@code ROLLBACK} (it is the success end and
 * a rollback is its own transition), and {@code GATE_FAILED}/{@code FAILED}/{@code REJECTED}
 * are side ends rather than hard ends: they can be rerun, resubmitted, closed or exited.
 *
 * <p><strong>DONE is not in {@link ItemStatus#isTerminal()} on purpose</strong> (Task 7
 * hand-off): it has an outgoing {@code ROLLBACK} edge. "Terminal" in the progress sense
 * therefore means "no longer blocking", not "no outgoing edges"; the truly edge-free states
 * are {@code CLOSED}, {@code TERMINATED} and {@code ROLLED_BACK}.
 */
public final class ItemStateMachine {

    /** States that accept the "any non-terminal" CLOSE/EXIT rows. */
    private static final Set<ItemStatus> CLOSABLE = EnumSet.of(
        ItemStatus.PENDING, ItemStatus.BUILDING, ItemStatus.TESTING, ItemStatus.GATE_PENDING,
        ItemStatus.SUBMITTED, ItemStatus.PENDING_APPROVAL, ItemStatus.READY,
        ItemStatus.PROD_DEPLOYING, ItemStatus.GATE_FAILED, ItemStatus.FAILED,
        ItemStatus.REJECTED);

    private static final Map<ItemStatus, Map<ItemEvent, ItemStatus>> TRANSITIONS = build();

    private ItemStateMachine() {
    }

    /**
     * The events the given status accepts, never {@code null}. Closed-out states answer
     * with an empty set instead of {@code null}.
     */
    public static Set<ItemEvent> allowedEvents(ItemStatus from) {
        Objects.requireNonNull(from, "from must not be null");
        return TRANSITIONS.get(from).keySet();
    }

    /**
     * Applies {@code event} to {@code item} and returns the next main status without
     * persisting anything; the caller stores it with a {@code row_version} CAS.
     *
     * @throws IllegalTransitionException when the status does not accept the event
     */
    public static ItemStatus transit(ReleaseItem item, ItemEvent event) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(event, "event must not be null");
        ItemStatus from = item.status();
        Objects.requireNonNull(from, "item.status must not be null");
        ItemStatus to = TRANSITIONS.get(from).get(event);
        if (to == null) {
            throw new IllegalTransitionException(from, event);
        }
        return to;
    }

    private static Map<ItemStatus, Map<ItemEvent, ItemStatus>> build() {
        Map<ItemStatus, Map<ItemEvent, ItemStatus>> matrix = new EnumMap<>(ItemStatus.class);
        for (ItemStatus status : ItemStatus.values()) {
            matrix.put(status, new EnumMap<>(ItemEvent.class));
        }

        // The forward online path (tech design §3.2).
        put(matrix, ItemStatus.PENDING, ItemEvent.PUBLISH, ItemStatus.BUILDING);
        put(matrix, ItemStatus.BUILDING, ItemEvent.BUILD_OK, ItemStatus.TESTING);
        put(matrix, ItemStatus.BUILDING, ItemEvent.BUILD_FAIL, ItemStatus.FAILED);
        put(matrix, ItemStatus.TESTING, ItemEvent.GATE_DECIDE, ItemStatus.GATE_PENDING);
        put(matrix, ItemStatus.TESTING, ItemEvent.TEST_DEPLOY_FAIL, ItemStatus.FAILED);
        put(matrix, ItemStatus.GATE_PENDING, ItemEvent.SUBMIT, ItemStatus.SUBMITTED);
        put(matrix, ItemStatus.SUBMITTED, ItemEvent.NEED_APPROVAL,
            ItemStatus.PENDING_APPROVAL);
        put(matrix, ItemStatus.SUBMITTED, ItemEvent.FREEZE_HIT, ItemStatus.PENDING_APPROVAL);
        put(matrix, ItemStatus.SUBMITTED, ItemEvent.NO_APPROVAL, ItemStatus.READY);
        put(matrix, ItemStatus.PENDING_APPROVAL, ItemEvent.APPROVE_ALL, ItemStatus.READY);
        put(matrix, ItemStatus.PENDING_APPROVAL, ItemEvent.REJECT, ItemStatus.REJECTED);
        put(matrix, ItemStatus.PENDING_APPROVAL, ItemEvent.GATE_INVALID, ItemStatus.GATE_FAILED);
        put(matrix, ItemStatus.READY, ItemEvent.EXECUTE, ItemStatus.PROD_DEPLOYING);
        put(matrix, ItemStatus.READY, ItemEvent.FREEZE_HIT, ItemStatus.PENDING_APPROVAL);
        put(matrix, ItemStatus.PROD_DEPLOYING, ItemEvent.PROD_OK, ItemStatus.DONE);
        put(matrix, ItemStatus.PROD_DEPLOYING, ItemEvent.PROD_FAIL, ItemStatus.FAILED);

        // Gate revocation and rollback.
        put(matrix, ItemStatus.GATE_PENDING, ItemEvent.CHECK_INVALID, ItemStatus.GATE_FAILED);
        put(matrix, ItemStatus.GATE_PENDING, ItemEvent.TEST_REVOKE, ItemStatus.GATE_FAILED);
        put(matrix, ItemStatus.TESTING, ItemEvent.CHECK_INVALID, ItemStatus.GATE_FAILED);
        put(matrix, ItemStatus.TESTING, ItemEvent.TEST_REVOKE, ItemStatus.GATE_FAILED);
        put(matrix, ItemStatus.DONE, ItemEvent.ROLLBACK, ItemStatus.ROLLED_BACK);

        // Rerun and resubmit.
        put(matrix, ItemStatus.GATE_FAILED, ItemEvent.RERUN, ItemStatus.TESTING);
        put(matrix, ItemStatus.FAILED, ItemEvent.RERUN, ItemStatus.TESTING);
        put(matrix, ItemStatus.REJECTED, ItemEvent.RESUBMIT, ItemStatus.SUBMITTED);

        // "Any non-terminal" rows.
        for (ItemStatus status : CLOSABLE) {
            put(matrix, status, ItemEvent.CLOSE, ItemStatus.CLOSED);
            put(matrix, status, ItemEvent.EXIT, ItemStatus.TERMINATED);
        }

        for (Map.Entry<ItemStatus, Map<ItemEvent, ItemStatus>> entry : matrix.entrySet()) {
            entry.setValue(Collections.unmodifiableMap(entry.getValue()));
        }
        return Collections.unmodifiableMap(matrix);
    }

    private static void put(Map<ItemStatus, Map<ItemEvent, ItemStatus>> matrix,
                            ItemStatus from, ItemEvent event, ItemStatus to) {
        matrix.get(from).put(event, to);
    }
}