package com.sf.release.domain.pipeline;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The effective unit-level pipeline configuration (P6-C design §1/§2): the environment-check
 * switch, the two timeouts and the batch order. A missing/stored-malformed row is represented by
 * the constants below, so a read failure degrades to the pre-P6-C behaviour instead of failing a
 * pipeline.
 *
 * <p><strong>The safe subset only.</strong> There is deliberately no node enable/disable flag and
 * no topology editing: the four-node graph (and the library/daily variants) is fixed, and this
 * record only carries the three knobs the design allows.
 *
 * <p><strong>Out-of-range values degrade, they do not propagate.</strong> The application validates
 * a save against {@link #MIN_BUILD_TIMEOUT_MINUTES}..{@link #MAX_BUILD_TIMEOUT_MINUTES} and
 * {@link #MIN_DEPLOY_TIMEOUT_MINUTES}..{@link #MAX_DEPLOY_TIMEOUT_MINUTES}; the compact constructor
 * still clamps a value read from a row written by an older/foreign build, so a corrupt row can never
 * install an absurd deadline.
 */
public record UnitPipelineConfig(
    boolean envChecksEnabled,
    int buildTimeoutMinutes,
    int deployTimeoutMinutes,
    BatchOrder batchOrder) {

    /** Build timeout bounds of the save endpoint (P6-C design §1). */
    public static final int MIN_BUILD_TIMEOUT_MINUTES = 5;
    public static final int MAX_BUILD_TIMEOUT_MINUTES = 240;

    /** Deploy timeout bounds of the save endpoint (P6-C design §1). */
    public static final int MIN_DEPLOY_TIMEOUT_MINUTES = 1;
    public static final int MAX_DEPLOY_TIMEOUT_MINUTES = 120;

    /** The pre-P6-C build deadline (60 minutes) and the table default. */
    public static final int DEFAULT_BUILD_TIMEOUT_MINUTES = 60;

    /** The pre-P6-C test/production deploy deadline (8 minutes) and the table default. */
    public static final int DEFAULT_DEPLOY_TIMEOUT_MINUTES = 8;

    /** The effective configuration when no row exists or the row cannot be read. */
    public static final UnitPipelineConfig DEFAULTS = new UnitPipelineConfig(true,
        DEFAULT_BUILD_TIMEOUT_MINUTES, DEFAULT_DEPLOY_TIMEOUT_MINUTES, BatchOrder.FORWARD);

    public UnitPipelineConfig {
        buildTimeoutMinutes = clamp(buildTimeoutMinutes, MIN_BUILD_TIMEOUT_MINUTES,
            MAX_BUILD_TIMEOUT_MINUTES, DEFAULT_BUILD_TIMEOUT_MINUTES);
        deployTimeoutMinutes = clamp(deployTimeoutMinutes, MIN_DEPLOY_TIMEOUT_MINUTES,
            MAX_DEPLOY_TIMEOUT_MINUTES, DEFAULT_DEPLOY_TIMEOUT_MINUTES);
        batchOrder = batchOrder == null ? BatchOrder.FORWARD : batchOrder;
    }

    /** The build deadline to store on {@code BUILD}/{@code LIB_PUBLISH} tasks. */
    public Duration buildDeadline() {
        return Duration.ofMinutes(buildTimeoutMinutes);
    }

    /** The deploy deadline to store on the deploy tasks. */
    public Duration deployDeadline() {
        return Duration.ofMinutes(deployTimeoutMinutes);
    }

    /** Whether a newly created online instance renumbers its production batches in reverse. */
    public boolean reverseBatches() {
        return batchOrder == BatchOrder.REVERSE;
    }

    /**
     * The enabled check types after applying the unit's environment-check switch to the platform
     * set (P6-C design §2: "平台集合 ∩ 单元开关").
     *
     * <p>Only the three environment-level checks ({@code SECURITY_DYNAMIC}/{@code AUTO_TEST}/
     * {@code DB_NORM}) are removed; the image-level checks and the library artifact checks stay
     * enabled, because the switch is about the checks that need a deployed environment. A
     * {@code null} platform set means "all checks" (the same meaning it has in the factory), and a
     * {@code null} config means "no row", i.e. the switch is on.
     *
     * <p>The result may be empty when the platform enables environment checks only and the unit
     * disables them; the publish use case turns that into an explicit configuration error instead
     * of pre-generating an empty {@code VERIFY} node.
     */
    public static Set<CheckType> effectiveChecks(Set<CheckType> platformEnabled,
                                                 UnitPipelineConfig config) {
        Set<CheckType> platform = platformEnabled == null
            ? Set.of(CheckType.values()) : Set.copyOf(platformEnabled);
        if (config == null || config.envChecksEnabled()) {
            return platform;
        }
        EnumSet<CheckType> effective = EnumSet.noneOf(CheckType.class);
        for (CheckType type : platform) {
            if (type.stage() != CheckType.CheckStage.ENV) {
                effective.add(type);
            }
        }
        return Collections.unmodifiableSet(effective);
    }

    /** The three environment-level checks the switch controls. */
    public static Set<CheckType> environmentChecks() {
        EnumSet<CheckType> checks = EnumSet.noneOf(CheckType.class);
        for (CheckType type : CheckType.values()) {
            if (type.stage() == CheckType.CheckStage.ENV) {
                checks.add(type);
            }
        }
        return Collections.unmodifiableSet(checks);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }
}