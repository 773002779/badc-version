package com.sf.release.web.dto;

import com.sf.release.application.settings.PipelineConfigService.UnitPipelineConfigView;

import java.time.Instant;

/**
 * Request/response bodies of the P6-C unit pipeline configuration (design §3).
 *
 * <p><strong>Strings only on the request.</strong> The four fields arrive as strings and the
 * application service parses and range-checks them (40000), because the web package may not depend
 * on the domain layer: {@code batchOrder} is a {@code BatchOrder} enum value and
 * {@code envChecksEnabled} a boolean, both owned by the application/domain layers.
 *
 * <p><strong>Machine value plus label on the response.</strong> {@code batchOrder} is the stable
 * {@code FORWARD}/{@code REVERSE} value and {@code batchOrderLabel} is the Chinese label the page
 * shows, exactly like the gate check names/labels of P6-B. {@code configured} tells the page
 * whether a stored row exists (false = the documented defaults).
 */
public final class PipelineConfigDtos {

    private PipelineConfigDtos() {
    }

    /** Translates the application view into the page contract. */
    public static PipelineConfigResponse of(UnitPipelineConfigView view) {
        return new PipelineConfigResponse(view.unitId(), view.envChecksEnabled(),
            view.buildTimeoutMinutes(), view.deployTimeoutMinutes(), view.batchOrder(),
            view.batchOrderLabel(), view.configured(), view.updatedBy(), view.updatedAt());
    }

    /** The 「流水线配置」 block: full replacement form; every field is required on a save. */
    public record SavePipelineConfigRequest(
        String envChecksEnabled,
        String buildTimeoutMinutes,
        String deployTimeoutMinutes,
        String batchOrder) {
    }

    /**
     * The stored (or default) configuration plus its metadata. {@code batchOrder} carries the
     * machine value, {@code batchOrderLabel} the Chinese label; a missing row yields the documented
     * defaults with {@code configured:false} and null metadata.
     */
    public record PipelineConfigResponse(
        long unitId,
        boolean envChecksEnabled,
        int buildTimeoutMinutes,
        int deployTimeoutMinutes,
        String batchOrder,
        String batchOrderLabel,
        boolean configured,
        String updatedBy,
        Instant updatedAt) {
    }
}