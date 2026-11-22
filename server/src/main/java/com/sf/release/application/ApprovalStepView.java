package com.sf.release.application;

import java.time.Instant;

/**
 * One step of an approval chain as the web layer sees it: the domain enums are flattened to their
 * names so {@code web} never depends on {@code domain}, and each name is accompanied by its Chinese
 * label so the page does not have to keep its own copy of the vocabulary.
 *
 * <p>{@code decidedBy}/{@code decidedAt}/{@code comment} are null while the step is pending and
 * for a canceled step (it was never decided). A rejected step always carries the reason.
 */
public record ApprovalStepView(int stepNo, String role, String roleLabel, String status,
                               String statusLabel, String decidedBy, Instant decidedAt,
                               String comment) {
}