package com.sf.release.domain.resource;

/**
 * Environment class of a DE. {@code PROD}, {@code DR} and {@code BLUE} may carry a
 * deployment batch number; {@code deploy_batch_no} decides the serial order (C14).
 */
public enum EnvKind {
    TEST,
    DAILY,
    PROD,
    DR,
    BLUE;

    /** Chinese label for the page; the enum name is the API/persisted value. */
    public String label() {
        return switch (this) {
            case TEST -> "测试";
            case DAILY -> "日常";
            case PROD -> "生产";
            case DR -> "容灾";
            case BLUE -> "蓝绿";
        };
    }
}