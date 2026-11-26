package com.sf.release.web.dto;

import com.sf.release.application.CodingConnAppService.CodingConnView;
import com.sf.release.application.CodingConnAppService.CodingProbeView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request and response bodies of {@code /api/v1/coding}. Responses are built from the
 * application read models, so the web layer never touches a domain entity or the encrypted
 * credential column.
 *
 * <p>{@code token} is optional on save: a blank value keeps the stored credential (the page only
 * knows the masked hint). The very first save without a token is rejected by the service.
 */
public final class CodingDtos {

    private CodingDtos() {
    }

    /** Save form of the tenant-wide connection. */
    public record SaveConnRequest(
        @NotBlank(message = "CODING 地址不能为空")
        @Size(max = 255, message = "CODING 地址长度不能超过 255")
        String baseUrl,

        @NotBlank(message = "CODING 团队不能为空")
        @Size(max = 64, message = "CODING 团队长度不能超过 64")
        String team,

        @NotBlank(message = "CODING 项目不能为空")
        @Size(max = 64, message = "CODING 项目长度不能超过 64")
        String project,

        @Size(max = 4096, message = "CODING 凭证长度不能超过 4096")
        String token) {
    }

    /**
     * Optional test form: every field falls back to the stored connection, so the page can test
     * just the field the operator changed (or test an unsaved draft).
     */
    public record TestConnRequest(
        @Size(max = 255, message = "CODING 地址长度不能超过 255")
        String baseUrl,

        @Size(max = 64, message = "CODING 团队长度不能超过 64")
        String team,

        @Size(max = 64, message = "CODING 项目长度不能超过 64")
        String project,

        @Size(max = 4096, message = "CODING 凭证长度不能超过 4096")
        String token) {
    }

    /**
     * Current connection, or {@code configured:false} with every other field null. {@code scope}/
     * {@code subsystemId} tell the caller which row was answered (GLOBAL, or a subsystem override
     * — PART-05).
     */
    public record ConnResponse(
        boolean configured,
        Long id,
        String scope,
        Long subsystemId,
        String baseUrl,
        String team,
        String project,
        String tokenHint,
        Instant updatedAt,
        Instant lastProbeAt,
        Boolean lastProbeOk,
        String lastProbeMessage) {

        public static ConnResponse absent() {
            return new ConnResponse(false, null, null, null, null, null, null, null, null, null, null, null);
        }

        public static ConnResponse of(CodingConnView view) {
            return new ConnResponse(true, view.id(), view.scope(), view.subsystemId(),
                view.baseUrl(), view.team(), view.project(),
                view.tokenHint(), view.updatedAt(), view.lastProbeAt(), view.lastProbeOk(),
                view.lastProbeMessage());
        }
    }

    /** Probe outcome; {@code message} is the operator-facing reason of a failure. */
    public record ProbeResponse(
        boolean ok,
        String status,
        String message,
        int buildJobs,
        int deployJobs,
        Instant at,
        boolean persisted) {

        public static ProbeResponse of(CodingProbeView view) {
            return new ProbeResponse(view.ok(), view.status(), view.message(),
                view.buildJobs(), view.deployJobs(), view.at(), view.persisted());
        }
    }
}