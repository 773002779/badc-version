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
    BLUE
}