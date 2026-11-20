package com.sf.release.domain.pipeline;

/**
 * The seven check types of the gate (requirements §7.1, tech design §7):
 * four image-level checks that run after {@code IMG_BUILD} succeeded and three environment-level
 * checks that run after the test deployment succeeded.
 *
 * <p><strong>Persistence.</strong> There is no check-catalogue table in phase 1, so the
 * {@code VERIFY} node pre-generates one {@code CHECK} {@link NodeTask} per value of this enum.
 * The task's {@code target_id} carries {@link #code()} because the {@code node_task}
 * idempotency key has no check-type column; {@link NodeTask#checkType()} maps it back.
 * {@code CheckResult.check_type} (a later task) should store {@link #name()}.
 */
public enum CheckType {
    SONAR(1, CheckStage.IMAGE),
    COMPONENT_VULN(2, CheckStage.IMAGE),
    ARCHITECTURE(3, CheckStage.IMAGE),
    VERSION_DEFECT(4, CheckStage.IMAGE),
    SECURITY_DYNAMIC(5, CheckStage.ENV),
    AUTO_TEST(6, CheckStage.ENV),
    DB_NORM(7, CheckStage.ENV);

    /** Stage a check belongs to: image-level or environment-level. */
    public enum CheckStage {
        IMAGE,
        ENV
    }

    private final int code;
    private final CheckStage stage;

    CheckType(int code, CheckStage stage) {
        this.code = code;
        this.stage = stage;
    }

    /** Stable code persisted in {@code node_task.target_id} (1..7; 0 means "no target"). */
    public int code() {
        return code;
    }

    public CheckStage stage() {
        return stage;
    }

    /**
     * Resolves the check type stored in a {@code CHECK} task's {@code target_id}.
     *
     * @throws IllegalArgumentException when the code is not a known check type
     */
    public static CheckType fromCode(long code) {
        for (CheckType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown check type code: " + code);
    }
}