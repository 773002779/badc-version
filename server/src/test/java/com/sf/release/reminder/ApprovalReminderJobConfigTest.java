package com.sf.release.reminder;

import com.sf.release.application.reminder.ApprovalReminderService;
import com.sf.release.infra.config.JobConfig;
import com.sf.release.infra.job.ApprovalReminderJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The P6-E job switch (design §1/§4 item 7): {@code ApprovalReminderJob} is registered by default
 * and removed when {@code release.approval.reminder-enabled=false}, exactly like the other
 * {@code @ConditionalOnProperty} jobs in {@link JobConfig}. The other jobs' switches are turned off
 * in the runner so their dependencies are not needed.
 */
class ApprovalReminderJobConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(JobConfig.class)
        .withPropertyValues(
            "release.reconcile.enabled=false",
            "release.gate.reconcile-enabled=false",
            "release.freeze.expiry-enabled=false",
            "release.unit-lease.renew-enabled=false",
            "release.notification.dispatch-enabled=false")
        .withBean(ApprovalReminderService.class, () -> mock(ApprovalReminderService.class))
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void the_reminder_job_is_registered_by_default() {
        runner.run(context -> assertThat(context).hasSingleBean(ApprovalReminderJob.class));
    }

    @Test
    void the_switch_removes_the_reminder_job() {
        runner.withPropertyValues("release.approval.reminder-enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(ApprovalReminderJob.class));
    }
}
