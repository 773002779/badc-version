package com.sf.release.application;

import java.time.Instant;
import java.util.List;

/**
 * One deployable version of the rollback drawer (rollback design section 2): the image digest, when
 * it last went to production, the publish number and operator of that deployment, how many DEs it
 * covers, and the environment families it was deployed to.
 *
 * <p>The page shows the first entry as "current online version"; the rest are the historical
 * targets. {@code executedBy} is {@code null} for deployments recorded before V25 added the operator
 * column, and the page renders a dash.
 */
public record RollbackVersionView(
    String digest,
    Instant finishedAt,
    String publishNo,
    String executedBy,
    int batchCount,
    List<Environment> environments) {

    public RollbackVersionView {
        environments = environments == null ? List.of() : List.copyOf(environments);
    }

    /** One environment family the image runs in; both the machine value and its label. */
    public record Environment(String kind, String label) {
    }
}