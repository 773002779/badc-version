package com.sf.release.infra.engine;

import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.EngineException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Selects a {@link CicdEngine} by its {@code name()} ({@code CODING} in phase 1; a future
 * {@code SELF_HOSTED} adapter only needs to be registered here).
 *
 * <p>Duplicate names are rejected eagerly: silently picking one of two adapters for the same
 * engine type would hide a wiring error. An unknown type is reported as
 * {@code ENGINE_UNAVAILABLE} rather than {@code null}.
 */
public final class EngineRegistry {

    private final Map<String, CicdEngine> engines;

    public EngineRegistry(List<CicdEngine> engines) {
        Map<String, CicdEngine> byName = new LinkedHashMap<>();
        if (engines != null) {
            for (CicdEngine engine : engines) {
                if (engine == null || engine.name() == null || engine.name().isBlank()) {
                    throw new IllegalArgumentException("engine name is required");
                }
                String name = engine.name().trim().toUpperCase(Locale.ROOT);
                CicdEngine previous = byName.putIfAbsent(name, engine);
                if (previous != null) {
                    throw new IllegalStateException("duplicate engine name: " + name);
                }
            }
        }
        this.engines = Collections.unmodifiableMap(byName);
    }

    /** Returns the engine for {@code engineType} (case-insensitive), if registered. */
    public Optional<CicdEngine> find(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(engines.get(engineType.trim().toUpperCase(Locale.ROOT)));
    }

    /** Returns the engine for {@code engineType} or fails with {@code ENGINE_UNAVAILABLE}. */
    public CicdEngine require(String engineType) {
        return find(engineType).orElseThrow(() -> EngineException.unavailable(
            "引擎未注册：" + engineType + "（已注册：" + names() + "）"));
    }

    /** Registered engine names, in registration order. */
    public Set<String> names() {
        return engines.keySet();
    }
}