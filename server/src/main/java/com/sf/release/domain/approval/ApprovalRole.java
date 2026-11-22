package com.sf.release.domain.approval;

/**
 * The roles that may sign a production approval step (tech design 4.4). A step names a role, not
 * a person: anybody holding the role can sign, so a personnel change never strands a release.
 *
 * <p>The names are part of the API contract and match {@code com.sf.release.common.security.Roles}.
 */
public enum ApprovalRole {
    /** 运维组长 — single signature when the planned time is outside the release window. */
    OPS_LEAD,
    /** 部门经理 — the second signature a freeze adds (双签). */
    DEPT_MANAGER
}