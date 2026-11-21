package com.sf.release.infra.engine;

import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.gate.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a vendor severity word to the platform {@link Severity} through
 * {@code coding.severityMapping}.
 *
 * <p>An unmapped word yields {@code null} and a WARN, never {@link Severity#NONE}: "no findings"
 * and "we do not understand this level" must not collapse into the same value, or an unmapped
 * vocabulary would silently pass a release. A mapping key that is not a {@link Severity} is a
 * configuration error and fails loudly at the first use.
 */
public final class CodingSeverityMapper {

    private static final Logger log = LoggerFactory.getLogger(CodingSeverityMapper.class);

    private final CodingProperties properties;

    public CodingSeverityMapper(CodingProperties properties) {
        this.properties = properties;
    }

    /**
     * @param word   the raw engine level word, for example {@code "BLOCKER"}
     * @param source diagnostic origin ({@code "check.query"}), included in the WARN line
     * @return the mapped severity, or {@code null} when the word is absent or unknown
     */
    public Severity map(String word, String source) {
        String normalized = word == null ? "" : word.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : properties.getSeverityMapping().entrySet()) {
            Severity severity = parseKey(entry.getKey(), source);
            if (entry.getValue() == null) {
                continue;
            }
            for (String engineWord : entry.getValue()) {
                if (engineWord != null && normalized.equalsIgnoreCase(engineWord.trim())) {
                    return severity;
                }
            }
        }
        log.warn("CODING severity word is not mapped: {} (source={}), keeping the previous level",
            normalized, source);
        return null;
    }

    /** Parses a mapping key; the {@code "#{S1}"} form of the design brief is accepted too. */
    static Severity parseKey(String key, String source) {
        String normalized = key == null ? "" : key.trim();
        if (normalized.startsWith("#{") && normalized.endsWith("}")) {
            normalized = normalized.substring(2, normalized.length() - 1).trim();
        }
        Severity severity = Severity.parse(normalized.toUpperCase(Locale.ROOT));
        if (severity == null) {
            throw EngineException.unavailable(
                "severityMapping 的键不是 Severity：" + key + "（source=" + source + "）");
        }
        return severity;
    }
}