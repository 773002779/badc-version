package com.sf.release.domain.pipeline;

/**
 * Task type of a {@link NodeTask} (tech design §4.2 {@code node_task.task_type}). Phase 1
 * pre-generates {@code CODE_CHECK}, {@code PKG_BUILD}, {@code IMG_BUILD}, {@code DEPLOY} and
 * {@code CHECK}; {@code AUTO_TEST} and {@code ROLLBACK} are part of the vocabulary but are
 * produced by later tasks (gate automation, rollback).
 */
public enum TaskType {
    CODE_CHECK,
    PKG_BUILD,
    IMG_BUILD,
    DEPLOY,
    CHECK,
    AUTO_TEST,
    ROLLBACK
}