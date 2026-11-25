package com.sf.release.domain.notification;

/**
 * The object a notification is about (design section 2, {@code object_type}).
 *
 * <p>The value is the stable machine word the row stores and the page uses to decide where a click
 * goes; the Chinese label, if one is ever needed, belongs to the page, not here.
 */
public enum NotificationObjectType {
    ORDER,
    ITEM,
    UNIT,
    FREEZE,
    WAIVER,
    INSTANCE,
    /** A 发布任务 (P5-B): the archive event is about the task, not about one of its releases. */
    TASK
}