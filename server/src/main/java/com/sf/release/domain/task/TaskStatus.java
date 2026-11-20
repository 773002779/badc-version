package com.sf.release.domain.task;

/**
 * Lifecycle of a release task: {@code ACTIVE} while 变更项 may still be added,
 * {@code ARCHIVED} (read-only, records retained) after 退出任务 / 归档 (FR-T-05).
 */
public enum TaskStatus {
    ACTIVE,
    ARCHIVED
}