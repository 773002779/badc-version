package com.sf.release.infra.engine;

import com.sf.release.domain.resource.CodingConn;

/**
 * Port for a lightweight CODING connectivity check (Task 10 supplies the HTTP
 * implementation; tests inject a fake). The service decrypts the stored credential and
 * passes it separately, so no plaintext token ever enters the persisted entity or its
 * {@code toString}.
 */
public interface CodingProbe {

    /**
     * Probes one connection.
     *
     * @param conn       stored connection configuration (no credential plaintext)
     * @param credential decrypted token, valid only for the duration of this call
     */
    ProbeResult probe(CodingConn conn, String credential);
}
