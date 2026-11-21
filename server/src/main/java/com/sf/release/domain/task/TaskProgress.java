package com.sf.release.domain.task;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Task progress (FR-T-04 / J9), derived from the item statuses and never persisted:
 * {@code done/total}, where only {@link ItemStatus#isDone()} counts as done and the rate of
 * a task without items is {@code 0}. {@code hasBlocking} is true as soon as one item is in
 * an in-flight state ({@link ItemStatus#isBlocking()}).
 *
 * <p>Task 7 defines the rule over the {@link ItemStatus} enum and receives an empty status
 * list; the {@code release_item} query arrives with Task 8. {@link #ofCounts(Map)} applies
 * the same rule to the grouped {@code status -> count} rows of the task list, so the rule
 * stays defined once even when the read side aggregates in SQL.
 */
public record TaskProgress(long done, long total, double rate, boolean hasBlocking) {

    public static TaskProgress of(Collection<ItemStatus> statuses) {
        Map<ItemStatus, Long> counts = new EnumMap<>(ItemStatus.class);
        if (statuses != null) {
            for (ItemStatus status : statuses) {
                counts.merge(status, 1L, Long::sum);
            }
        }
        return ofCounts(counts);
    }

    /**
     * Same rule as {@link #of(Collection)}, from the {@code status -> count} rows of one task.
     * Counts of zero contribute nothing; an absent status does not need a row.
     */
    public static TaskProgress ofCounts(Map<ItemStatus, Long> counts) {
        Map<ItemStatus, Long> values = counts == null ? Map.of() : counts;
        long total = 0;
        long done = 0;
        boolean hasBlocking = false;
        for (Map.Entry<ItemStatus, Long> entry : values.entrySet()) {
            long count = entry.getValue() == null ? 0 : entry.getValue();
            total += count;
            if (entry.getKey().isDone()) {
                done += count;
            }
            if (count > 0 && entry.getKey().isBlocking()) {
                hasBlocking = true;
            }
        }
        double rate = total == 0 ? 0.0d : (double) done / total;
        return new TaskProgress(done, total, rate, hasBlocking);
    }
}