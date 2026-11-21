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
 * {@code gate_check.check_kind} stores {@link #name()} and its {@code stage} column stores
 * {@link #stage()}.
 *
 * <p>{@link #label()} is the Chinese operator-facing name of the check; it is the label the
 * verification panel and the gate reasons show, so a renamed check only changes this one place.
 */
public enum CheckType {
    SONAR(1, CheckStage.IMAGE, "Sonar 扫描"),
    COMPONENT_VULN(2, CheckStage.IMAGE, "应用组件漏洞"),
    ARCHITECTURE(3, CheckStage.IMAGE, "架构规范"),
    VERSION_DEFECT(4, CheckStage.IMAGE, "版本缺陷"),
    SECURITY_DYNAMIC(5, CheckStage.ENV, "安全动态测试"),
    AUTO_TEST(6, CheckStage.ENV, "自动化测试"),
    DB_NORM(7, CheckStage.ENV, "数据库规范");

    /** Stage a check belongs to: image-level or environment-level. */
    public enum CheckStage {
        IMAGE,
        ENV
    }

    private final int code;
    private final CheckStage stage;
    private final String label;

    CheckType(int code, CheckStage stage, String label) {
        this.code = code;
        this.stage = stage;
        this.label = label;
    }

    /** Stable code persisted in {@code node_task.target_id} (1..7; 0 means "no target"). */
    public int code() {
        return code;
    }

    public CheckStage stage() {
        return stage;
    }

    /** Chinese operator-facing label, for example {@code Sonar 扫描}. */
    public String label() {
        return label;
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

    /**
     * Resolves a persisted {@code gate_check.check_kind}; an unknown name yields {@code null}
     * (a row written by a newer version must not break an older reader).
     */
    public static CheckType fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}