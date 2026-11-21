package com.sf.release.infra.engine;

import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a vendor status word to the platform {@link EngineStatus} through the configured
 * {@code coding.statusMapping} (tech design §6.1/§6.2).
 *
 * <p>One mapping is shared by the query adapter and the webhook receiver, so a build callback
 * and a polled query can never disagree about the same word. An unmapped word is reported as
 * {@link EngineStatus#UNKNOWN} (never as an engine outage: the reconcile loop turns repeated
 * call failures into a TIMEOUT, which would falsely kill a live job) and logged at WARN with the
 * raw word, so the operator can extend the YAML mapping. A mapping key that is not an
 * {@link EngineStatus} is a configuration error and fails loudly.
 */
public final class CodingStatusMapper {

    private static final Logger log = LoggerFactory.getLogger(CodingStatusMapper.class);

    private final CodingProperties properties;

    public CodingStatusMapper(CodingProperties properties) {
        this.properties = properties;
    }

    /**
     * @param word   the raw engine status word, for example {@code "FINISHED"}
     * @param source diagnostic origin of the word ({@code "build.query"},
     *               {@code "webhook"}), included in the WARN line
     */
    public EngineStatus map(String word, String source) {
        String normalized = word == null ? "" : word.trim();
        for (Map.Entry<String, List<String>> entry : properties.getStatusMapping().entrySet()) {
            EngineStatus status = parseStatusKey(entry.getKey(), source);
            if (entry.getValue() == null) {
                continue;
            }
            for (String engineWord : entry.getValue()) {
                if (engineWord != null && normalized.equalsIgnoreCase(engineWord.trim())) {
                    return status;
                }
            }
        }
        log.warn("CODING status word is not mapped: {} (source={}), reporting UNKNOWN",
            normalized, source);
        return EngineStatus.UNKNOWN;
    }

    /**
     * Parses a mapping key. The design brief writes keys as {@code "#{QUEUED}"}; both that form
     * and the plain {@code "QUEUED"} map to the same status.
     */
    static EngineStatus parseStatusKey(String key, String source) {
        String normalized = key == null ? "" : key.trim();
        if (normalized.startsWith("#{") && normalized.endsWith("}")) {
            normalized = normalized.substring(2, normalized.length() - 1).trim();
        }
        try {
            return EngineStatus.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw EngineException.unavailable(
                "statusMapping 的键不是 EngineStatus：" + key + "（source=" + source + "）");
        }
    }
}