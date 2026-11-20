package com.sf.release.domain.task;

/**
 * The role a member holds on a release task (FR-T-03). The names are exactly the platform
 * role names of {@code com.sf.release.common.security.Roles}: {@code DEV}, {@code TEST},
 * {@code OPS}, {@code PRODUCT} and {@code OWNER}.
 *
 * <p>{@code PRODUCT} exists on purpose: freeze notifications are addressed by the
 * 产品/研发/测试/运维 member roles (C22), so a task member list must be able to name the
 * product owner even though that person holds no platform permission point.
 */
public enum MemberRole {
    DEV,
    TEST,
    OPS,
    PRODUCT,
    OWNER
}