package com.sf.release.domain.pipeline;

/**
 * Task type of a {@link NodeTask} (tech design §4.2 {@code node_task.task_type}). The online
 * pipeline pre-generates {@code CODE_CHECK}, {@code PKG_BUILD}, {@code IMG_BUILD}, {@code DEPLOY}
 * and {@code CHECK}; {@code AUTO_TEST} and {@code ROLLBACK} are part of the vocabulary but are
 * produced by later tasks (gate automation, rollback).
 *
 * <p>{@code DAILY_DEPLOY} is the daily release's own task kind (P5-A design §2): it is appended
 * so no existing persisted value changes meaning. The daily deploy node is generated with the
 * generic {@code DEPLOY} tasks (design §3: one per target DE), so {@code DAILY_DEPLOY} is the
 * reserved vocabulary for a future dedicated daily deploy job, exactly like the
 * {@code coding_task_binding.task_kind = DAILY_DEPLOY} binding it mirrors.
 */
public enum TaskType {
    CODE_CHECK,
    PKG_BUILD,
    IMG_BUILD,
    DEPLOY,
    CHECK,
    AUTO_TEST,
    ROLLBACK,
    DAILY_DEPLOY
}