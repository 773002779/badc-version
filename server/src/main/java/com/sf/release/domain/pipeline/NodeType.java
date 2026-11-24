package com.sf.release.domain.pipeline;

/**
 * Execution node of a pipeline instance (tech design §5.1). An online instance has exactly four
 * nodes in this order: {@code BUILD → TEST_DEPLOY → VERIFY → PROD_DEPLOY}.
 *
 * <p>{@code DAILY_DEPLOY} is the daily release's second (and last) node (P5-A design §3): the
 * daily pipeline is {@code BUILD → DAILY_DEPLOY}, reusing the unit's build chain and deploying to
 * the daily (or, when the unit has none, the test) DEs. It was <strong>appended</strong> so no
 * existing persisted value changes meaning; an online instance never contains it.
 *
 * <p>The second-party library pipeline ({@code LIB_BUILD/LIB_SECURITY/LIB_PUBLISH}) is still
 * outside the implemented slices; adding it later must not renumber the existing values either.
 */
public enum NodeType {
    BUILD,
    TEST_DEPLOY,
    VERIFY,
    PROD_DEPLOY,
    DAILY_DEPLOY
}