package com.sf.release.domain.task;

/**
 * Lifecycle of a release task: {@code ACTIVE} while 变更项 may still be added,
 * {@code ARCHIVED} (read-only, records retained) after 退出任务 / 归档 (FR-T-05).
 */
public enum TaskStatus {
    ACTIVE,
    ARCHIVED;

    /** Chinese label for the API/page; the enum name stays the machine value. */
    public String label() {
        return switch (this) {
            case ACTIVE -> "进行中";
            case ARCHIVED -> "已归档";
        };
    }
}