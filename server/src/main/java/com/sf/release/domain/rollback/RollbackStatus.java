package com.sf.release.domain.rollback;

/**
 * Lifecycle of one production rollback record (rollback design section 3/5).
 *
 * <p>{@link #RUNNING}: the rollback instance was created and has not finished. {@link #SUCCESS}:
 * the instance reached {@code DONE} and the source 变更项 was moved to {@code ROLLED_BACK} when it
 * was still {@code DONE}. {@link #FAILED}: the instance failed; the 变更项 is not changed. The
 * terminal values are written with a status compare-and-set, so a duplicate completion event is a
 * no-op instead of a second finish.
 */
public enum RollbackStatus {
    RUNNING,
    SUCCESS,
    FAILED;

    /** Chinese label for the page; the enum name is the API value. */
    public String label() {
        return switch (this) {
            case RUNNING -> "执行中";
            case SUCCESS -> "成功";
            case FAILED -> "失败";
        };
    }
}