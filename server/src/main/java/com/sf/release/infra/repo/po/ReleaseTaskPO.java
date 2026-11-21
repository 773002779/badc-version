package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.task.ReleaseTask;
import com.sf.release.domain.task.TaskStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Persistence object for {@code release_task}. Column names are derived from the
 * camel-case fields; {@code status} is stored as its uppercase enum name.
 */
@TableName("release_task")
public class ReleaseTaskPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long subsystemId;
    private String name;
    private String creator;
    private String purpose;
    private LocalDate planFinishDate;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public static ReleaseTaskPO of(ReleaseTask task) {
        ReleaseTaskPO po = new ReleaseTaskPO();
        po.id = task.id();
        po.subsystemId = task.subsystemId();
        po.name = task.name();
        po.creator = task.creator();
        po.purpose = task.purpose();
        po.planFinishDate = task.planFinishDate();
        po.status = task.status().name();
        po.createdAt = task.createdAt();
        po.updatedAt = task.updatedAt();
        return po;
    }

    public ReleaseTask toDomain() {
        return new ReleaseTask(id, subsystemId, name, creator, purpose, planFinishDate,
            status(), createdAt, updatedAt);
    }

    private TaskStatus status() {
        return status == null || status.isBlank() ? TaskStatus.ACTIVE : TaskStatus.valueOf(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public void setSubsystemId(Long subsystemId) {
        this.subsystemId = subsystemId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public LocalDate getPlanFinishDate() {
        return planFinishDate;
    }

    public void setPlanFinishDate(LocalDate planFinishDate) {
        this.planFinishDate = planFinishDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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