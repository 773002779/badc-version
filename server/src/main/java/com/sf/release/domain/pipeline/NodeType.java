package com.sf.release.domain.pipeline;

/**
 * Execution node of a pipeline instance (tech design §5.1). Phase 1 publishes a deployment
 * unit online, so an initial instance has exactly these four nodes in this order:
 * {@code BUILD → TEST_DEPLOY → VERIFY → PROD_DEPLOY}.
 *
 * <p>The daily pipeline ({@code DAILY_DEPLOY}) and the second-party library pipeline
 * ({@code LIB_BUILD/LIB_SECURITY/LIB_PUBLISH}) are outside phase 1 and therefore have no
 * constants here yet; adding them later must not renumber the existing values.
 */
public enum NodeType {
    BUILD,
    TEST_DEPLOY,
    VERIFY,
    PROD_DEPLOY
}