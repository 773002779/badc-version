package com.sf.release.domain.engine;

/**
 * Which kind of engine job a status or log query addresses. The external id alone is not
 * enough: build and deploy jobs have separate query endpoints and may even share a numeric
 * namespace, so the caller must state the kind it recorded when the job was triggered.
 *
 * <p>{@code PUBLISH} is the second-party library's artifact upload job (P6-A design §3): it is
 * a third namespace, so a publish callback is not mistaken for a build or deploy one. It was
 * appended so the existing kinds keep their meaning.
 */
public enum JobKind {
    BUILD,
    DEPLOY,
    PUBLISH
}