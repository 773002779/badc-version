package com.sf.release.application;

import java.time.LocalDateTime;

/**
 * A span of a release window as the web layer sees it: local date-times in the window zone.
 *
 * <p>It exists so no domain type leaks into {@code web}: the read models of this package flatten
 * the domain records into plain fields, and the controller only maps application records to DTOs.
 */
public record WindowSpanView(LocalDateTime start, LocalDateTime end) {
}