package com.sf.release.infra.engine;

import java.util.function.Supplier;

/**
 * The connection source the {@link CodingEngine} resolves each call through. It is a
 * {@link Supplier} of the {@code GLOBAL} connection (the Phase 1 contract), extended with a
 * per-unit resolution for PART-05: a start-class call that carries a {@code unitId} resolves the
 * owning subsystem's {@code SUBSYSTEM} override first and falls back to {@code GLOBAL}.
 *
 * <p>The engine keeps accepting a plain {@code Supplier} (the tests inject lambdas); a plain
 * supplier simply always answers the GLOBAL connection.
 */
public interface CodingConnectionSource extends Supplier<CodingEngine.Connection> {

    /**
     * The connection of a call that carries a unit: the unit's subsystem override when one is
     * configured, else the GLOBAL connection. {@code unitId <= 0} answers the GLOBAL connection.
     */
    default CodingEngine.Connection forUnit(long unitId) {
        return get();
    }
}
