package com.sf.release.domain.pipeline;

/**
 * Kind of execution a {@link PipelineInstance} represents (tech design §4.2 {@code exec_type}).
 * Only {@code ONLINE} instances are created in phase 1; daily self-check instances
 * ({@code DAILY}) do not create a release order, do not take the unit lock and do not change
 * the item status (§5.1).
 */
public enum ExecType {
    ONLINE,
    DAILY
}