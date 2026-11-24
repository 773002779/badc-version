/**
 * The small audit vocabulary the two P4-C pages need locally.
 *
 * The backend owns the enums: every row carries `actionLabel`, so a table never needs these labels.
 * The filter dropdown is the one exception — the API has no "list the actions" endpoint — so the
 * common actions are copied verbatim from `server/.../domain/audit/AuditAction.java`. An action that
 * is not listed here is still readable through 全部动作 and still renders its server-side label.
 * Object types have no label field on the row at all, so the closed `AuditObjectType` vocabulary is
 * mirrored here for display (`objectTypeLabel`). Keep both lists in sync with the backend enum.
 */

export interface AuditVocabularyItem {
  value: string
  label: string
}

/**
 * The common actions, grouped for the dropdown. Deliberately not the whole enum: the rare/P5
 * actions would double the list without being filterable-in-practice, and 全部动作 covers them.
 */
export const AUDIT_ACTION_GROUPS: ReadonlyArray<{
  label: string
  actions: AuditVocabularyItem[]
}> = [
  {
    label: '发布与审批',
    actions: [
      { value: 'PUBLISH', label: '发起发布' },
      { value: 'SUBMIT_PROD', label: '提交生产' },
      { value: 'EXECUTE_PROD', label: '生产执行' },
      { value: 'RERUN', label: '重新发布' },
      { value: 'APPROVE', label: '审批通过' },
      { value: 'REJECT', label: '审批驳回' }
    ]
  },
  {
    label: '门禁与管控',
    actions: [
      { value: 'GATE_TEST_PASS', label: '测试通过' },
      { value: 'FREEZE_CREATE', label: '创建封板' },
      { value: 'FREEZE_RELEASE', label: '解除封板' },
      { value: 'ROLLBACK_EXECUTE', label: '执行回滚' }
    ]
  },
  {
    label: '配置与资源',
    actions: [
      { value: 'RESOURCE_UPDATE', label: '更新资源' },
      { value: 'SETTING_UPDATE', label: '更新设置' },
      { value: 'WINDOW_UPDATE', label: '更新发布窗口' }
    ]
  }
]

/** `AuditObjectType` in full: 9 closed values, each with the enum's Chinese label. */
export const AUDIT_OBJECT_TYPES: ReadonlyArray<AuditVocabularyItem> = [
  { value: 'ORDER', label: '发布单' },
  { value: 'ITEM', label: '变更项' },
  { value: 'UNIT', label: '部署单元' },
  { value: 'SUBSYSTEM', label: '系统' },
  { value: 'TASK', label: '发布任务' },
  { value: 'FREEZE', label: '生产封板' },
  { value: 'WAIVER', label: '检查豁免' },
  { value: 'ROLLBACK', label: '生产回滚' },
  { value: 'SETTING', label: '平台设置' }
]

/** The Chinese object-type label; an unknown stored code degrades to the code itself. */
export function objectTypeLabel(code?: string | null): string {
  if (!code) return '—'
  return AUDIT_OBJECT_TYPES.find((t) => t.value === code)?.label ?? code
}

/** The first 8 characters of a trace id; the full value stays on the element's `title`. */
export function shortTraceId(traceId?: string | null): string {
  if (!traceId) return '—'
  return traceId.length > 8 ? traceId.slice(0, 8) : traceId
}

/** Pretty-print a stored `before`/`after` JSON text; a non-JSON legacy value is shown verbatim. */
export function prettyJson(raw?: string | null): string {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}