package com.sf.release.domain.pipeline.port;

/**
 * The batch-boundary guard of the production deploy node (tech design 4.7): "触发下一批之前检查
 * (a) 系统是否有生效封板 (b) 门禁是否 INVALID/非豁免 BLOCK；命中则把实例置 PAUSED 并写
 * status_reason".
 *
 * <p><strong>Why a port.</strong> The freeze and gate answers come from the application use cases
 * ({@code FreezeService#active}, {@code GateService#decisionForInstance}), and the domain must not
 * depend on {@code application}/{@code infra} (ArchUnit). The handler therefore asks this one
 * question, and the implementation lives next to the services it needs.
 *
 * <p><strong>One question, one reason.</strong> The answer is the operator-facing sentence for
 * {@code pipeline_instance.status_reason}, or {@code null} when the next batch may start. It is
 * evaluated <em>at the boundary only</em> — never per deploy task — because a freeze declared while a
 * batch is running must not abort that batch; the next boundary is the place to stop (design 4.7).
 */
public interface ProductionBoundaryProbe {

    /**
     * Why the next production batch may not be triggered now, or {@code null} when it may.
     *
     * @param instanceId the pipeline instance about to trigger a batch
     */
    String blockedReason(long instanceId);
}