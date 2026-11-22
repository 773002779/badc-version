package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.resource.CodingConn;
import com.sf.release.domain.resource.CodingScope;

import java.time.Instant;

/**
 * Persistence object for {@code coding_conn}. The only credential column is
 * {@code credential_cipher}, always the AES-GCM output of {@code CredentialCipher};
 * plaintext tokens never reach this class.
 */
@TableName("coding_conn")
public class CodingConnPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String scope;
    private Long subsystemId;
    private String baseUrl;
    private String team;
    private String project;
    private String credentialCipher;
    private Instant createdAt;
    private Instant updatedAt;

    // Operational metadata of the last stored-connection probe (V20); not part of the domain
    // entity, which only models the connection itself.
    private Instant lastProbeAt;
    private Boolean lastProbeOk;
    private String lastProbeMessage;

    public static CodingConnPO of(CodingConn conn) {
        CodingConnPO po = new CodingConnPO();
        po.id = conn.id();
        po.scope = conn.scope().name();
        po.subsystemId = conn.subsystemId();
        po.baseUrl = conn.baseUrl();
        po.team = conn.team();
        po.project = conn.project();
        po.credentialCipher = conn.credentialCipher();
        po.createdAt = conn.createdAt();
        po.updatedAt = conn.updatedAt();
        return po;
    }

    public CodingConn toDomain() {
        return new CodingConn(id, scope(), subsystemId == null ? 0L : subsystemId, baseUrl, team,
            project, credentialCipher, createdAt, updatedAt);
    }

    private CodingScope scope() {
        return scope == null || scope.isBlank() ? CodingScope.GLOBAL : CodingScope.valueOf(scope);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Long getSubsystemId() {
        return subsystemId;
    }

    public void setSubsystemId(Long subsystemId) {
        this.subsystemId = subsystemId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getCredentialCipher() {
        return credentialCipher;
    }

    public void setCredentialCipher(String credentialCipher) {
        this.credentialCipher = credentialCipher;
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

    public Instant getLastProbeAt() {
        return lastProbeAt;
    }

    public void setLastProbeAt(Instant lastProbeAt) {
        this.lastProbeAt = lastProbeAt;
    }

    public Boolean getLastProbeOk() {
        return lastProbeOk;
    }

    public void setLastProbeOk(Boolean lastProbeOk) {
        this.lastProbeOk = lastProbeOk;
    }

    public String getLastProbeMessage() {
        return lastProbeMessage;
    }

    public void setLastProbeMessage(String lastProbeMessage) {
        this.lastProbeMessage = lastProbeMessage;
    }
}
