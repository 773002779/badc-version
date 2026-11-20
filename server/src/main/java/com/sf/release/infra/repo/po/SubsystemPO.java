package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.resource.Subsystem;
import com.sf.release.domain.resource.SubsystemStatus;

import java.time.Instant;

/**
 * Persistence object for {@code subsystem}. Column names are derived from the camel-case
 * fields (map-underscore-to-camel-case is enabled globally); {@code status} is stored as
 * the uppercase enum name.
 */
@TableName("subsystem")
public class SubsystemPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;
    private String ownerId;
    private String agentId;
    private String status;
    private String description;
    private String gitUrl;
    private String codingProject;
    private Instant createdAt;
    private Instant updatedAt;

    public static SubsystemPO of(Subsystem subsystem) {
        SubsystemPO po = new SubsystemPO();
        po.id = subsystem.id();
        po.name = subsystem.name();
        po.ownerId = subsystem.ownerId();
        po.agentId = subsystem.agentId();
        po.status = subsystem.status().name();
        po.description = subsystem.description();
        po.gitUrl = subsystem.gitUrl();
        po.codingProject = subsystem.codingProject();
        po.createdAt = subsystem.createdAt();
        po.updatedAt = subsystem.updatedAt();
        return po;
    }

    public Subsystem toDomain() {
        return new Subsystem(id, name, ownerId, agentId, status(), description, gitUrl,
            codingProject, createdAt, updatedAt);
    }

    private SubsystemStatus status() {
        return status == null || status.isBlank()
            ? SubsystemStatus.ACTIVE
            : SubsystemStatus.valueOf(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getGitUrl() {
        return gitUrl;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public String getCodingProject() {
        return codingProject;
    }

    public void setCodingProject(String codingProject) {
        this.codingProject = codingProject;
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