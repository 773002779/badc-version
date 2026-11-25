package com.sf.release.pipelineconfig;

import com.sf.release.application.audit.AuditService;
import com.sf.release.application.settings.PipelineConfigService;
import com.sf.release.domain.pipeline.UnitPipelineConfig;
import com.sf.release.infra.repo.CodingTaskBindingMapper;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.EnvDeMapper;
import com.sf.release.infra.repo.MybatisPipelineRepository;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.repo.PipelineNodeMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.UnitPipelineConfigMapper;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.UnitPipelineConfigPO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fail-safe half of P6-C (design §2: "读失败/无行回退常量默认"): a missing row and an
 * unreadable row both answer {@code null}, which every consumer turns into the documented
 * constants. The pipeline never fails because its configuration row is absent or broken.
 *
 * <p>Plain Mockito: the mappers are the seam that can fail, so no database (and no Spring context)
 * is needed to prove the degradation.
 */
class PipelineConfigFallbackTest {

    private final PipelineInstanceMapper instanceMapper = mock(PipelineInstanceMapper.class);
    private final UnitPipelineConfigMapper configMapper = mock(UnitPipelineConfigMapper.class);
    private final MybatisPipelineRepository repository = new MybatisPipelineRepository(
        instanceMapper, mock(PipelineNodeMapper.class), mock(NodeTaskMapper.class),
        mock(ReleaseOrderMapper.class), mock(ReleaseItemMapper.class),
        mock(CodingTaskBindingMapper.class), mock(EnvDeMapper.class), mock(DeployUnitMapper.class),
        configMapper);

    @Test
    void an_absent_row_answers_null_and_the_handlers_use_their_constants() {
        when(instanceMapper.selectById(7L)).thenReturn(dailyInstance(7L, 42L));
        when(configMapper.selectById(42L)).thenReturn(null);

        assertThat(repository.pipelineConfigOf(7L)).isNull();
    }

    @Test
    void an_unreadable_row_degrades_to_null_instead_of_failing_the_pipeline() {
        when(instanceMapper.selectById(7L)).thenReturn(dailyInstance(7L, 42L));
        when(configMapper.selectById(42L)).thenThrow(new IllegalStateException("db down"));

        assertThat(repository.pipelineConfigOf(7L))
            .as("a broken configuration row must never block or shorten a pipeline")
            .isNull();
    }

    @Test
    void an_unknown_instance_answers_null() {
        when(instanceMapper.selectById(7L)).thenReturn(null);
        assertThat(repository.pipelineConfigOf(7L)).isNull();
    }

    @Test
    void the_service_read_degrades_to_null_and_the_defaults_are_the_documented_on_es() {
        when(configMapper.selectById(42L)).thenThrow(new IllegalStateException("db down"));
        PipelineConfigService service = new PipelineConfigService(configMapper,
            mock(DeployUnitMapper.class), mock(AuditService.class));

        assertThat(service.configOfUnit(42L)).isNull();
        assertThat(UnitPipelineConfig.DEFAULTS.envChecksEnabled()).isTrue();
        assertThat(UnitPipelineConfig.DEFAULTS.buildTimeoutMinutes()).isEqualTo(60);
        assertThat(UnitPipelineConfig.DEFAULTS.deployTimeoutMinutes()).isEqualTo(8);
        assertThat(UnitPipelineConfig.DEFAULTS.batchOrder().name()).isEqualTo("FORWARD");
    }

    @Test
    void a_row_carrying_missing_or_out_of_range_columns_is_read_with_the_defaults() {
        UnitPipelineConfigPO po = new UnitPipelineConfigPO();
        po.setUnitId(42L);
        po.setEnvChecksEnabled(null);
        po.setBuildTimeoutMinutes(241);
        po.setDeployTimeoutMinutes(null);
        po.setBatchOrder("SIDEWAYS");
        when(instanceMapper.selectById(7L)).thenReturn(dailyInstance(7L, 42L));
        when(configMapper.selectById(42L)).thenReturn(po);

        UnitPipelineConfig config = repository.pipelineConfigOf(7L);

        assertThat(config).isNotNull();
        assertThat(config.envChecksEnabled()).as("a missing switch means on").isTrue();
        assertThat(config.buildTimeoutMinutes())
            .as("an out-of-range stored value degrades instead of installing an absurd deadline")
            .isEqualTo(60);
        assertThat(config.deployTimeoutMinutes()).isEqualTo(8);
        assertThat(config.batchOrder().name()).isEqualTo("FORWARD");
    }

    private static PipelineInstancePO dailyInstance(long id, long unitId) {
        PipelineInstancePO po = new PipelineInstancePO();
        po.setId(id);
        po.setOrderId(null);
        po.setUnitId(unitId);
        return po;
    }
}