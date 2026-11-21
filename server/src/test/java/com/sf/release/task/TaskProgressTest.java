package com.sf.release.task;

import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.TaskProgress;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Progress aggregation from {@code status -> count} rows: the task list must derive
 * {@code done/total/rate/hasBlocking} from one grouped query, with exactly the same rule as
 * {@link TaskProgress#of(java.util.Collection)}.
 */
class TaskProgressTest {

    @Test
    void a_task_without_items_has_a_zero_rate_and_no_blocking_item() {
        TaskProgress progress = TaskProgress.ofCounts(Map.of());

        assertThat(progress.done()).isZero();
        assertThat(progress.total()).isZero();
        assertThat(progress.rate()).isZero();
        assertThat(progress.hasBlocking()).isFalse();
    }

    @Test
    void done_counts_only_the_done_state_and_in_flight_states_block() {
        Map<ItemStatus, Long> counts = new EnumMap<>(ItemStatus.class);
        counts.put(ItemStatus.DONE, 2L);
        counts.put(ItemStatus.PENDING, 1L);
        counts.put(ItemStatus.FAILED, 1L);

        TaskProgress progress = TaskProgress.ofCounts(counts);

        assertThat(progress.done()).isEqualTo(2);
        assertThat(progress.total()).isEqualTo(4);
        assertThat(progress.rate()).isEqualTo(0.5d);
        assertThat(progress.hasBlocking()).isTrue();
    }

    @Test
    void closed_out_states_are_not_blocking_and_done_alone_is_no_blocker() {
        Map<ItemStatus, Long> counts = new EnumMap<>(ItemStatus.class);
        counts.put(ItemStatus.DONE, 1L);
        counts.put(ItemStatus.FAILED, 1L);
        counts.put(ItemStatus.CLOSED, 1L);
        counts.put(ItemStatus.ROLLED_BACK, 1L);

        TaskProgress progress = TaskProgress.ofCounts(counts);

        assertThat(progress.done()).isEqualTo(1);
        assertThat(progress.total()).isEqualTo(4);
        assertThat(progress.rate()).isEqualTo(0.25d);
        assertThat(progress.hasBlocking()).isFalse();
    }
}