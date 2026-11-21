package com.sf.release.common.security;

import java.util.Set;

/**
 * The sixteen button-level permission points of the platform. The strings are part of
 * the API contract and must not be changed or renumbered.
 */
public final class Permissions {

    public static final String TASK_CREATE = "task:create";
    public static final String ITEM_CREATE = "item:create";
    public static final String RELEASE_PUBLISH = "release:publish";
    public static final String RELEASE_DAILY = "release:daily";
    public static final String GATE_TEST_PASS = "gate:test-pass";
    public static final String GATE_TEST_REVOKE = "gate:test-revoke";
    public static final String GATE_WAIVE = "gate:waive";
    public static final String CONTROL_SUBMIT_PROD = "control:submit-prod";
    public static final String CONTROL_EXECUTE_PROD = "control:execute-prod";
    public static final String CONTROL_APPROVE = "control:approve";
    public static final String CONTROL_FREEZE = "control:freeze";
    public static final String CONTROL_UNFREEZE = "control:unfreeze";
    public static final String ROLLBACK_CREATE = "rollback:create";
    public static final String ROLLBACK_EXECUTE = "rollback:execute";
    public static final String LOCK_FORCE_RELEASE = "lock:force-release";
    public static final String SETTING_MANAGE = "setting:manage";

    /** All permission points; handy for seed verification and settings screens. */
    public static final Set<String> ALL = Set.of(
        TASK_CREATE, ITEM_CREATE, RELEASE_PUBLISH, RELEASE_DAILY,
        GATE_TEST_PASS, GATE_TEST_REVOKE, GATE_WAIVE,
        CONTROL_SUBMIT_PROD, CONTROL_EXECUTE_PROD, CONTROL_APPROVE,
        CONTROL_FREEZE, CONTROL_UNFREEZE,
        ROLLBACK_CREATE, ROLLBACK_EXECUTE, LOCK_FORCE_RELEASE, SETTING_MANAGE);

    private Permissions() {
    }
}
