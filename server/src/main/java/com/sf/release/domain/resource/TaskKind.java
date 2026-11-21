package com.sf.release.domain.resource;

/**
 * The four CODING task references a unit can bind (C23). The first three are required
 * before a unit may publish; {@code DAILY_DEPLOY} is optional and falls back to
 * {@code TEST_DEPLOY} for daily releases.
 */
public enum TaskKind {
    BUILD,
    TEST_DEPLOY,
    PROD_DEPLOY,
    DAILY_DEPLOY
}