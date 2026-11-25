package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.resource.BranchMode;
import com.sf.release.domain.resource.DeployUnit;
import com.sf.release.domain.resource.UnitStatus;
import com.sf.release.domain.resource.UnitType;

import java.time.Instant;

/**
 * Persistence object for {@code deploy_unit}. Column names are derived from the
 * camel-case fields; enums are stored as their uppercase names.
 */
@TableName("deploy_unit")
public class DeployUnitPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long subsystemId;
    private String name;
    private String type;
    private String du;
    private String ownerId;
    private String frameworkTemplate;
    private String branchMode;
    private String status;
    private String description;
    private String modulePath;
    private String deployType;
    private String imageType;
    private String imageName;
    /** Filter-only labels (V16): never part of a build or deploy payload. */
    private String module;
    private String tag;
    /** LIB only (V29): the Maven coordinates of the published artifact; NULL for a UNIT. */
    private String artifactGroupId;
    private String artifactArtifactId;
    private Instant createdAt;
    private Instant updatedAt;

    public static DeployUnitPO of(DeployUnit unit) {
        DeployUnitPO po = new DeployUnitPO();
        po.id = unit.id();
        po.subsystemId = unit.subsystemId();
        po.name = unit.name();
        po.type = unit.type().name();
        po.du = unit.du();
        po.ownerId = unit.ownerId();
        po.frameworkTemplate = unit.frameworkTemplate();
        po.branchMode = unit.branchMode().name();
        po.status = unit.status().name();
        po.description = unit.description();
        po.modulePath = unit.modulePath();
        po.deployType = unit.deployType();
        po.imageType = unit.imageType();
        po.imageName = unit.imageName();
        po.module = unit.module();
        po.tag = unit.tag();
        po.artifactGroupId = unit.artifactGroupId();
        po.artifactArtifactId = unit.artifactArtifactId();
        po.createdAt = unit.createdAt();
        po.updatedAt = unit.updatedAt();
        return po;
    }

    public DeployUnit toDomain() {
        return new DeployUnit(id, subsystemId, name, type(), du, ownerId, frameworkTemplate,
            branchMode(), status(), description, modulePath, deployType, imageType, imageName,
            module, tag, artifactGroupId, artifactArtifactId, createdAt, updatedAt);
    }

    private UnitType type() {
        return type == null || type.isBlank() ? UnitType.UNIT : UnitType.valueOf(type);
    }

    private BranchMode branchMode() {
        return branchMode == null || branchMode.isBlank()
            ? BranchMode.STANDARD
            : BranchMode.valueOf(branchMode);
    }

    private UnitStatus status() {
        return status == null || status.isBlank()
            ? UnitStatus.ACTIVE
            : UnitStatus.valueOf(status);
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDu() {
        return du;
    }

    public void setDu(String du) {
        this.du = du;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getFrameworkTemplate() {
        return frameworkTemplate;
    }

    public void setFrameworkTemplate(String frameworkTemplate) {
        this.frameworkTemplate = frameworkTemplate;
    }

    public String getBranchMode() {
        return branchMode;
    }

    public void setBranchMode(String branchMode) {
        this.branchMode = branchMode;
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

    public String getModulePath() {
        return modulePath;
    }

    public void setModulePath(String modulePath) {
        this.modulePath = modulePath;
    }

    public String getDeployType() {
        return deployType;
    }

    public void setDeployType(String deployType) {
        this.deployType = deployType;
    }

    public String getImageType() {
        return imageType;
    }

    public void setImageType(String imageType) {
        this.imageType = imageType;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    /** LIB only (V29): Maven groupId, {@code null} for a UNIT. */
    public String getArtifactGroupId() {
        return artifactGroupId;
    }

    public void setArtifactGroupId(String artifactGroupId) {
        this.artifactGroupId = artifactGroupId;
    }

    /** LIB only (V29): Maven artifactId, {@code null} for a UNIT. */
    public String getArtifactArtifactId() {
        return artifactArtifactId;
    }

    public void setArtifactArtifactId(String artifactArtifactId) {
        this.artifactArtifactId = artifactArtifactId;
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