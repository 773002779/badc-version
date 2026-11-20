package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.resource.CodingTaskBinding;
import com.sf.release.domain.resource.TaskKind;

import java.time.Instant;

/**
 * Persistence object for {@code coding_task_binding}. The unique key
 * {@code uk_unit_kind (unit_id, task_kind)} is the final guard against duplicate kinds.
 */
@TableName("coding_task_binding")
public class CodingTaskBindingPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long unitId;
    private String taskKind;
    private String taskId;
    private String paramsJson;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    public static CodingTaskBindingPO of(CodingTaskBinding binding) {
        CodingTaskBindingPO po = new CodingTaskBindingPO();
        po.id = binding.id();
        po.unitId = binding.unitId();
        po.taskKind = binding.taskKind().name();
        po.taskId = binding.taskId();
        po.paramsJson = binding.paramsJson();
        po.enabled = binding.enabled();
        po.createdAt = binding.createdAt();
        po.updatedAt = binding.updatedAt();
        return po;
    }

    public CodingTaskBinding toDomain() {
        return new CodingTaskBinding(id, unitId, TaskKind.valueOf(taskKind), taskId, paramsJson,
            enabled == null || enabled, createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public String getTaskKind() {
        return taskKind;
    }

    public void setTaskKind(String taskKind) {
        this.taskKind = taskKind;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}