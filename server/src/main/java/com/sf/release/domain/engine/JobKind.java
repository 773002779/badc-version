package com.sf.release.domain.engine;

/**
 * Which kind of engine job a status or log query addresses. The external id alone is not
 * enough: build and deploy jobs have separate query endpoints and may even share a numeric
 * namespace, so the caller must state the kind it recorded when the job was triggered.
 */
public enum JobKind {
    BUILD,
    DEPLOY
}