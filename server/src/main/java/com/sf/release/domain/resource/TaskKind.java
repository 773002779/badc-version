package com.sf.release.domain.resource;

/**
 * The CODING task references a unit can bind (C23). The first three are required
 * before a deployment unit may publish; {@code DAILY_DEPLOY} is optional and falls back to
 * {@code TEST_DEPLOY} for daily releases.
 *
 * <p>{@code LIB_PUBLISH} is the second-party library's artifact upload task (P6-A design §2):
 * a LIB unit binds it instead of the deploy kinds, and a LIB publish requires it. It is
 * appended so the existing values keep their meaning.
 */
public enum TaskKind {
    BUILD,
    TEST_DEPLOY,
    PROD_DEPLOY,
    DAILY_DEPLOY,
    LIB_PUBLISH
}