package com.sf.release.infra.repo.po;

/**
 * One {@code (digest, environment kind)} pair of the successful production deployments of a unit.
 * The rollback version list renders the environment chips from these rows.
 */
public class RollbackEnvKindRow {

    private String digest;
    private String envKind;

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public String getEnvKind() {
        return envKind;
    }

    public void setEnvKind(String envKind) {
        this.envKind = envKind;
    }
}