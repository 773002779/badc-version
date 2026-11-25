package com.sf.release.domain.pipeline;

/**
 * Kind of execution a {@link PipelineInstance} represents (tech design §4.2 {@code exec_type}).
 * Only {@code ONLINE} instances are created in phase 1; daily self-check instances
 * ({@code DAILY}) do not create a release order, do not take the unit lock and do not change
 * the item status (§5.1).
 *
 * <p>{@code LIB} is the second-party library pipeline (P6-A design §2): it is an online-style
 * execution — a real release order, the unit lock and the item status — but its graph is the
 * library's own three nodes instead of the deployment's four. Appended, so no existing persisted
 * value changes meaning; the column is wide enough ({@code VARCHAR(8)}) without a migration.
 */
public enum ExecType {
    ONLINE,
    DAILY,
    LIB
}