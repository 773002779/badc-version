/**
 * Artifact coordinates of a LIB unit (P6-A design §4). The backend requires both fields for a
 * `LIB` and ignores them for a `UNIT`; the client mirrors the documented format so the operator
 * gets the message before the request instead of after it.
 */
export const ARTIFACT_COORDINATE_MAX = 128

export const ARTIFACT_COORDINATE_RE = /^[A-Za-z0-9._-]{1,128}$/

/** The character rule, shown next to the two inputs. */
export const ARTIFACT_COORDINATE_HINT = '仅字母、数字、点、下划线、连字符，1-128 位'

/** Trimmed, non-empty, at most 128 chars and matching the backend's Maven coordinate rule. */
export function isArtifactCoordinate(value?: string | null): boolean {
  const trimmed = (value ?? '').trim()
  return trimmed.length > 0 && trimmed.length <= ARTIFACT_COORDINATE_MAX && ARTIFACT_COORDINATE_RE.test(trimmed)
}

/**
 * The first validation failure of one coordinate, or `null` when it is valid. `label` is the
 * user-facing field name (「制品 groupId」/「制品 artifactId」).
 */
export function artifactCoordinateError(value: string | null | undefined, label: string): string | null {
  const trimmed = (value ?? '').trim()
  if (!trimmed) return `${label}必填（二方库）`
  if (!isArtifactCoordinate(trimmed)) return `${label}格式不合法：${ARTIFACT_COORDINATE_HINT}`
  return null
}