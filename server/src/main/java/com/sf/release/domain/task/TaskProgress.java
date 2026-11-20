package com.sf.release.domain.task;

import java.util.Collection;
import java.util.List;

/**
 * Task progress (FR-T-04 / J9), derived from the item statuses and never persisted:
 * {@code done/total}, where only {@link ItemStatus#isDone()} counts as done and the rate of
 * a task without items is {@code 0}. {@code hasBlocking} is true as soon as one item is in
 * an in-flight state ({@link ItemStatus#isBlocking()}).
 *
 * <p>Task 7 defines the rule over the {@link ItemStatus} enum and receives an empty status
 * list; the {@code release_item} query arrives with Task 8.
 */
public record TaskProgress(long done, long total, double rate, boolean hasBlocking) {

    public static TaskProgress of(Collection<ItemStatus> statuses) {
        List<ItemStatus> items = statuses == null ? List.of() : List.copyOf(statuses);
        long total = items.size();
        long done = items.stream().filter(ItemStatus::isDone).count();
        double rate = total == 0 ? 0.0d : (double) done / total;
        boolean hasBlocking = items.stream().anyMatch(ItemStatus::isBlocking);
        return new TaskProgress(done, total, rate, hasBlocking);
    }
}