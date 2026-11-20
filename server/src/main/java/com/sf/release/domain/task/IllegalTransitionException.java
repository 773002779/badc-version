package com.sf.release.domain.task;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;

import java.util.Map;

/**
 * A {@link ItemEvent} that {@link ItemStatus} does not allow. It is a business failure, so
 * it is a {@link BizException} with {@link BizError#STATE_NOT_ALLOWED} (40001) and a payload
 * that always names the {@code currentStatus} — the UI refreshes its state from it instead
 * of guessing.
 */
public class IllegalTransitionException extends BizException {

    private final ItemStatus currentStatus;
    private final ItemEvent event;

    public IllegalTransitionException(ItemStatus currentStatus, ItemEvent event) {
        super(BizError.STATE_NOT_ALLOWED,
            "状态 " + currentStatus.name() + " 不允许事件 " + event.name(),
            Map.of("currentStatus", currentStatus.name(), "event", event.name()));
        this.currentStatus = currentStatus;
        this.event = event;
    }

    public ItemStatus getCurrentStatus() {
        return currentStatus;
    }

    public ItemEvent getEvent() {
        return event;
    }
}