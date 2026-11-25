package com.sf.release.infra.config;

import com.sf.release.application.FreezeService;
import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.GateWaiverService;
import com.sf.release.application.notification.NotificationDispatchService;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.domain.pipeline.port.ItemFailureRepairer;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.UnitLease;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.job.FreezeExpiryJob;
import com.sf.release.infra.job.GateReconcileJob;
import com.sf.release.infra.job.NotificationDispatchJob;
import com.sf.release.infra.job.ReconcileJob;
import com.sf.release.infra.job.UnitLeaseRenewJob;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.PipelineNodeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the scheduled jobs. {@link ReconcileJob} is conditional on
 * {@code release.reconcile.enabled} (default {@code true}); the test profile disables it because
 * the suite shares one schema and intends to invoke {@link ReconcileJob#run()} deterministically
 * instead of racing a background thread.
 */
@Configuration(proxyBeanMethods = false)
public class JobConfig {

    @Bean
    @ConditionalOnProperty(name = "release.reconcile.enabled", havingValue = "true",
        matchIfMissing = true)
    public ReconcileJob reconcileJob(DistributedLock lock,
                                     EngineRegistry engines,
                                     PipelineRepository repository,
                                     PipelineOrchestrator orchestrator,
                                     NodeTaskMapper taskMapper,
                                     PipelineNodeMapper nodeMapper,
                                     PipelineInstanceMapper instanceMapper,
                                     ItemFailureRepairer failureRepairer,
                                     @Value("${release.engine.type:CODING}") String engineType) {
        return new ReconcileJob(lock, engines, engineType, repository, orchestrator, taskMapper,
            nodeMapper, instanceMapper, failureRepairer);
    }

    /**
     * The gate half of the polling backstop: check polling (30s) and waiver expiry (daily). It has
     * its own switch because it holds a different lock and can be tuned separately; the test profile
     * turns it off so the shared schema is not mutated by a background thread.
     */
    @Bean
    @ConditionalOnProperty(name = "release.gate.reconcile-enabled", havingValue = "true",
        matchIfMissing = true)
    public GateReconcileJob gateReconcileJob(DistributedLock lock,
                                             GateCheckService checkService,
                                             GateService gateService,
                                             GateWaiverService waiverService) {
        return new GateReconcileJob(lock, checkService, gateService, waiverService);
    }

    /**
     * The daily 封板 expiry scan (tech design 4.2). Its own switch, like the gate job: the test
     * profile turns it off so the shared schema is not mutated by a background thread, and the
     * tests call {@code FreezeService.expireDue} explicitly.
     */
    @Bean
    @ConditionalOnProperty(name = "release.freeze.expiry-enabled", havingValue = "true",
        matchIfMissing = true)
    public FreezeExpiryJob freezeExpiryJob(FreezeService freezeService) {
        return new FreezeExpiryJob(freezeService);
    }

    /**
     * The 60s 部署单元 lease renewer (review M2): a pipeline paused at {@code PROD_DEPLOY} for
     * approval/freeze/batch reasons must keep its four-hour unit lease, otherwise another publish
     * could take the unit while the release still owns it. Its own switch, like the other jobs: the
     * test profile turns it off and the tests call {@link UnitLeaseRenewJob#renewLeases()} explicitly.
     *
     * <p>{@code release.unit-lease.max-paused-hours} is the <em>fallback</em> bound for how long an
     * abandoned pause keeps its lease renewed (independent review I3): the platform LOCK setting
     * ({@code pauseBoundHours}, P6-B design §2.3) overrides it and is read on every round; when that
     * row is missing or unreadable the job keeps this deployment value. A rejection nobody resubmits
     * must not lock the unit forever, and this phase has no close/force-release endpoint. Seven days
     * covers an approval and a long freeze; after that the four-hour lease is allowed to expire and
     * the unit is reclaimable.
     */
    @Bean
    @ConditionalOnProperty(name = "release.unit-lease.renew-enabled", havingValue = "true",
        matchIfMissing = true)
    public UnitLeaseRenewJob unitLeaseRenewJob(
        DeployUnitLockMapper lockMapper,
        UnitLease unitLease,
        NotificationService notifications,
        SettingsService settings,
        @Value("${release.unit-lease.max-paused-hours:168}") long maxPausedHours) {
        if (maxPausedHours <= 0) {
            throw new IllegalArgumentException(
                "release.unit-lease.max-paused-hours must be positive: " + maxPausedHours);
        }
        return new UnitLeaseRenewJob(lockMapper, unitLease, notifications,
            java.time.Duration.ofHours(maxPausedHours), settings);
    }

    /**
     * The 15s WeCom delivery loop (P4-B, notification design section 5). Its own switch, like the
     * other jobs: the test profile turns it off so the shared schema is not mutated by a background
     * thread, and the dispatch tests invoke the service explicitly.
     */
    @Bean
    @ConditionalOnProperty(name = "release.notification.dispatch-enabled", havingValue = "true",
        matchIfMissing = true)
    public NotificationDispatchJob notificationDispatchJob(
        NotificationDispatchService dispatchService) {
        return new NotificationDispatchJob(dispatchService);
    }
}