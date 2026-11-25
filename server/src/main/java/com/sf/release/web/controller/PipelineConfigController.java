package com.sf.release.web.controller;

import com.sf.release.application.settings.PipelineConfigService;
import com.sf.release.application.settings.PipelineConfigService.UnitPipelineConfigView;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.RequirePerm;
import com.sf.release.web.dto.PipelineConfigDtos;
import com.sf.release.web.dto.PipelineConfigDtos.PipelineConfigResponse;
import com.sf.release.web.dto.PipelineConfigDtos.SavePipelineConfigRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The P6-C unit-level pipeline configuration (design §3), GAP-09's safe subset:
 *
 * <ul>
 *   <li>{@code GET /api/v1/units/{unitId}/pipeline-config} — the stored configuration, or the
 *       documented defaults with {@code configured:false} when the unit never saved it;</li>
 *   <li>{@code PUT /api/v1/units/{unitId}/pipeline-config} — full replacement; out-of-range values
 *       are 40000 and one {@code SETTING_UPDATE} audit row (object {@code UNIT}, whole
 *       configuration before/after, {@code scope_unit_id = unitId}) is recorded.</li>
 * </ul>
 *
 * <p><strong>Permission and scope.</strong> Both endpoints require {@code setting:manage}; the
 * application service calls {@code ScopeGuard.assertUnitInScope} for a non-ALL caller (40009). The
 * unit exists check and the numeric parsing/range validation also live there, so a non-HTTP caller
 * behaves exactly like the page.
 *
 * <p><strong>Thin translation layer.</strong> The caller identity comes from the request (never the
 * body) and the controller imports no domain/infra class: the DTO carries strings and the
 * application view's machine values. This is the same shape as the P6-B settings controller.
 */
@RestController
@RequestMapping("/api/v1")
public class PipelineConfigController {

    private final PipelineConfigService pipelineConfigs;

    public PipelineConfigController(PipelineConfigService pipelineConfigs) {
        this.pipelineConfigs = pipelineConfigs;
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @DataScope
    @GetMapping("/units/{unitId}/pipeline-config")
    public ApiResponse<PipelineConfigResponse> get(@PathVariable long unitId) {
        return ApiResponse.ok(PipelineConfigDtos.of(pipelineConfigs.view(unitId)));
    }

    @RequirePerm(Permissions.SETTING_MANAGE)
    @DataScope
    @PutMapping("/units/{unitId}/pipeline-config")
    public ApiResponse<PipelineConfigResponse> save(
            @PathVariable long unitId,
            @RequestBody(required = false) SavePipelineConfigRequest request) {
        SavePipelineConfigRequest body = request == null
            ? new SavePipelineConfigRequest(null, null, null, null) : request;
        UnitPipelineConfigView saved = pipelineConfigs.save(unitId, body.envChecksEnabled(),
            body.buildTimeoutMinutes(), body.deployTimeoutMinutes(), body.batchOrder());
        return ApiResponse.ok(PipelineConfigDtos.of(saved));
    }
}