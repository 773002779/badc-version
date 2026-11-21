package com.sf.release.domain.pipeline.port;

/**
 * Read-only view of the commit a deployment unit would build, used by the rerun planner
 * (tech design §5.3, requirements §4.5 C15).
 *
 * <p>The probe is a port because the commit comparison needs the CODING repository and is
 * therefore an infrastructure concern; the domain planner only asks two questions:
 * <ul>
 *   <li>{@link #headSha(long, String)} — the current head of the branch the item builds;</li>
 *   <li>{@link #hasChangesUnder(long, String, String, String)} — whether anything below the
 *       unit's {@code module_path} changed since the item's {@code base_commit}. This is what
 *       keeps another team's commit in a shared repository from being mistaken for a change of
 *       this item (review focus 4).</li>
 * </ul>
 *
 * <p>Implementations may talk to the engine; tests inject a stub and never perform network I/O.
 */
public interface GitCommitProbe {

    /**
     * The current head commit of {@code branch} in the repository of {@code unitId}, or
     * {@code null} when the engine cannot report one (unknown branch, no repository binding).
     * A {@code null} answer is treated as "cannot prove the commit is unchanged" by callers.
     */
    String headSha(long unitId, String branch);

    /**
     * Whether at least one commit under {@code modulePath} exists between {@code baseSha} and
     * the current head of {@code branch}. An empty result means only other paths of the shared
     * repository changed, so the item's build output is unaffected.
     */
    boolean hasChangesUnder(long unitId, String branch, String modulePath, String baseSha);
}
