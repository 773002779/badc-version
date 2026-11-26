<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getApprovalSetting,
  getApproverSetting,
  getCodingConn,
  getGateSetting,
  getLockSetting,
  getMe,
  getNotificationSetting,
  getWaiverSetting,
  listSubsystems,
  saveApprovalSetting,
  saveApproverSetting,
  saveCodingConn,
  saveGateSetting,
  saveLockSetting,
  saveNotificationSetting,
  saveWaiverSetting,
  testCodingConn
} from '../api'
import { ApiError, currentUser, setCurrentUser } from '../api/http'
import { applyTheme, currentTheme } from '../theme'
import type { Theme } from '../theme'
import type {
  ApprovalSettingView,
  ApproverSettingView,
  CodingConnView,
  CodingProbeView,
  GateSettingView,
  LockSettingView,
  NotificationSettingView,
  WaiverSettingView
} from '../api/types'
import { formatDateTime } from '../utils/format'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import KeyValue from '../components/KeyValue.vue'

const theme = ref<Theme>(currentTheme())
const userId = ref(currentUser())
const checking = ref(false)
const backend = ref<{ ok: boolean; message: string; subsystems?: number } | null>(null)

// ---------------------------------------------------------------- CODING 对接
const canManage = ref(false)
const conn = ref<CodingConnView | null>(null)
const connLoading = ref(false)
const connError = ref<string | null>(null)
const saving = ref(false)
const testing = ref(false)
const probe = ref<CodingProbeView | null>(null)
const form = reactive({ baseUrl: '', team: '', project: '', token: '' })

const configured = computed(() => conn.value?.configured === true)

/** The probe box is green when the freshest evidence is a pass, red when it is a failure. */
const probeTone = computed(() => {
  const ok = probe.value ? probe.value.ok : conn.value?.lastProbeOk === true
  return ok ? 'is-ok' : 'is-fail'
})

/** Chip text: unconfigured, last probe failed, or simply configured. */
const connStatus = computed(() => {
  if (!configured.value) return { label: '未配置', tone: 'neutral' as const }
  if (conn.value?.lastProbeOk === false) return { label: '上次校验失败', tone: 'danger' as const }
  if (conn.value?.lastProbeOk === true) return { label: '已验证', tone: 'success' as const }
  return { label: '已配置 · 未验证', tone: 'warn' as const }
})

function onTheme(value: Theme) {
  theme.value = value
  applyTheme(value)
}

function saveUser() {
  setCurrentUser(userId.value)
  userId.value = currentUser()
  ElMessage.success('身份已保存，刷新页面后生效')
}

async function checkBackend() {
  checking.value = true
  try {
    const page = await listSubsystems(1, 1)
    backend.value = { ok: true, message: '连接正常', subsystems: Number(page.total) }
  } catch (e) {
    backend.value = { ok: false, message: e instanceof ApiError ? e.message : '连接失败' }
  } finally {
    checking.value = false
  }
}

function fillForm(view: CodingConnView) {
  form.baseUrl = view.baseUrl ?? ''
  form.team = view.team ?? ''
  form.project = view.project ?? ''
  form.token = ''
}

async function loadConn() {
  connLoading.value = true
  connError.value = null
  try {
    const me = await getMe()
    canManage.value = me.perms.includes('setting:manage')
    if (!canManage.value) {
      conn.value = null
      return
    }
    const view = await getCodingConn()
    conn.value = view
    if (view.configured) fillForm(view)
  } catch (e) {
    connError.value = e instanceof ApiError ? e.message : '读取 CODING 对接配置失败'
  } finally {
    connLoading.value = false
  }
}

async function saveConn() {
  if (!form.baseUrl.trim() || !form.team.trim() || !form.project.trim()) {
    ElMessage.warning('地址、团队与默认项目都是必填项')
    return
  }
  saving.value = true
  try {
    const view = await saveCodingConn({
      baseUrl: form.baseUrl.trim(),
      team: form.team.trim(),
      project: form.project.trim(),
      token: form.token.trim() || undefined
    })
    conn.value = view
    fillForm(view)
    probe.value = null
    ElMessage.success('CODING 连接已保存')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function testConn() {
  testing.value = true
  probe.value = null
  try {
    probe.value = await testCodingConn({
      baseUrl: form.baseUrl.trim() || undefined,
      team: form.team.trim() || undefined,
      project: form.project.trim() || undefined,
      token: form.token.trim() || undefined
    })
    if (probe.value.ok) {
      ElMessage.success('连接可用')
    } else {
      ElMessage.error('连接不可用，原因见下方')
    }
    if (probe.value.persisted) {
      // The backend recorded this as "last verified"; refresh so the header chip agrees.
      const view = await getCodingConn()
      conn.value = view
    }
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '测试连接失败')
  } finally {
    testing.value = false
  }
}

// ---------------------------------------------------------------- 消息通知 (P4-B)
//
// The second `setting:manage` block on this page, after CODING: the WeCom robot switch and webhook
// (design §5/§6). It fetches `/me` on its own because `loadConn` owns `canManage` only for its own
// request: reading the flag from that block would race on page load.
const notifyCanManage = ref(false)
const notifyView = ref<NotificationSettingView | null>(null)
const notifyLoading = ref(false)
const notifyError = ref<string | null>(null)
const notifySaving = ref(false)
const notifyEnabled = ref(false)
const notifyWebhook = ref('')

const notifyStatus = computed(() => {
  if (notifyLoading.value) return { label: '读取中…', tone: 'neutral' as const }
  if (!notifyCanManage.value) return { label: '只读', tone: 'neutral' as const }
  if (!notifyView.value) return { label: '默认关闭', tone: 'neutral' as const }
  return notifyView.value.wecomEnabled
    ? { label: '已开启', tone: 'success' as const }
    : { label: '已关闭', tone: 'neutral' as const }
})

const notifySubtitle = computed(() => {
  if (notifyLoading.value) return '读取中…'
  if (!notifyCanManage.value) return '仅站内通知 · 企微配置仅管理员可见'
  const view = notifyView.value
  if (!view) return '未保存过，默认仅站内通知'
  const state = view.wecomEnabled ? '企微已开启' : '企微已关闭'
  return view.updatedBy
    ? `${state} · ${view.updatedBy} 更新于 ${formatDateTime(view.updatedAt)}`
    : state
})

async function loadNotify() {
  notifyLoading.value = true
  notifyError.value = null
  try {
    const me = await getMe()
    notifyCanManage.value = me.perms.includes('setting:manage')
    if (!notifyCanManage.value) {
      // No permission means no read either: the endpoint answers 403, so do not call it.
      notifyView.value = null
      return
    }
    const view = await getNotificationSetting()
    notifyView.value = view
    notifyEnabled.value = view.wecomEnabled
    notifyWebhook.value = view.wecomWebhook ?? ''
  } catch (e) {
    notifyError.value = e instanceof ApiError ? e.message : '读取消息通知配置失败'
  } finally {
    notifyLoading.value = false
  }
}

async function saveNotify() {
  if (notifyEnabled.value && !notifyWebhook.value.trim()) {
    ElMessage.warning('开启企微通知时必须填写机器人地址')
    return
  }
  notifySaving.value = true
  try {
    const view = await saveNotificationSetting({
      wecomEnabled: notifyEnabled.value,
      wecomWebhook: notifyWebhook.value.trim() || null
    })
    notifyView.value = view
    notifyEnabled.value = view.wecomEnabled
    notifyWebhook.value = view.wecomWebhook ?? ''
    ElMessage.success('消息通知配置已保存')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    notifySaving.value = false
  }
}

// ---------------------------------------------------------------- 平台设置 (P6-B / P6-E)
//
// The four `setting:manage` blocks of design §4 (P6-B) and §2 (P6-E), after 消息通知:
// 门禁规则 / 审批人名单 / 锁超时 / 审批时效.
// They share one `/me` read and one loader: unlike the CODING and 消息通知 blocks (each owns its own
// permission flag because they are independent features), these four are one settings slice and
// always appear together, so a single permission check keeps the page from calling `/me` five times.
//
// The check labels below are display-only. The machine value is the contract the API stores and
// returns (`enabledChecks`); the Chinese names mirror CheckType so the checkbox reads like the
// verification panel, and an unknown value from a newer server is still rendered and kept.
const GATE_CHECKS: Array<{ value: string; label: string; stage: string }> = [
  { value: 'SONAR', label: 'Sonar 扫描', stage: '镜像检查 · 构建后' },
  { value: 'COMPONENT_VULN', label: '应用组件漏洞', stage: '镜像检查 · 构建后' },
  { value: 'ARCHITECTURE', label: '架构规范', stage: '镜像检查 · 构建后' },
  { value: 'VERSION_DEFECT', label: '版本缺陷', stage: '镜像检查 · 构建后' },
  { value: 'SECURITY_DYNAMIC', label: '安全动态测试', stage: '环境检查 · 测试部署后' },
  { value: 'AUTO_TEST', label: '自动化测试', stage: '环境检查 · 测试部署后' },
  { value: 'DB_NORM', label: '数据库规范', stage: '环境检查 · 测试部署后' }
]

const settingsLoading = ref(true)
const settingsCanManage = ref(false)
const gateTotal = GATE_CHECKS.length

const gateView = ref<GateSettingView | null>(null)
const gateEnabled = ref<string[]>([])
const gateError = ref<string | null>(null)
const gateSaving = ref(false)

const approverView = ref<ApproverSettingView | null>(null)
const opsLead = ref<string[]>([])
const deptManager = ref<string[]>([])
const opsLeadDraft = ref('')
const deptManagerDraft = ref('')
const approverError = ref<string | null>(null)
const approverSaving = ref(false)

const lockView = ref<LockSettingView | null>(null)
const pauseHours = ref('168')
const lockError = ref<string | null>(null)
const lockSaving = ref(false)

// 「豁免默认有效期」 (P6-F PART-06): lives inside the 门禁规则 block because it is a gate rule
// (J10), but owns its field and save — the section is one visual block, the API is its own
// `GET/PUT /api/v1/settings/waiver`. The input is a string so a bad value shows its reason
// instead of being silently coerced; the server re-checks 1–90 on save (40000).
const waiverView = ref<WaiverSettingView | null>(null)
const waiverDays = ref('7')
const waiverError = ref<string | null>(null)
const waiverSaving = ref(false)

const waiverInvalid = computed(() => {
  const days = Number(waiverDays.value)
  if (!Number.isInteger(days) || days < 1 || days > 90) return '豁免默认有效期必须是 1–90 的整数'
  return ''
})

async function fillWaiver(view: WaiverSettingView) {
  waiverView.value = view
  waiverDays.value = String(view.defaultDays)
}

async function saveWaiver() {
  if (waiverInvalid.value) {
    ElMessage.warning(waiverInvalid.value)
    return
  }
  waiverSaving.value = true
  try {
    const view = await saveWaiverSetting({ defaultDays: Number(waiverDays.value) })
    await fillWaiver(view)
    ElMessage.success('豁免默认有效期已保存')
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存豁免默认有效期失败'))
  } finally {
    waiverSaving.value = false
  }
}

// 「审批时效」 (P6-E §2): the switch plus the two thresholds of the pending-approval reminder scan.
// The inputs are strings so the page can show the reason for a bad value instead of silently
// coercing it; the server re-checks the same three rules on save (40000).
const approvalView = ref<ApprovalSettingView | null>(null)
const approvalEnabled = ref(true)
const remindHours = ref('12')
const escalateHours = ref('24')
const approvalError = ref<string | null>(null)
const approvalSaving = ref(false)

function errorMessage(e: unknown, fallback: string): string {
  return e instanceof ApiError ? e.message : fallback
}

/** All seven known checks, plus any value the server returned that this page does not know. */
const gateChecks = computed(() => {
  const known = new Set(GATE_CHECKS.map((check) => check.value))
  const extras = gateEnabled.value
    .filter((value) => !known.has(value))
    .map((value) => ({ value, label: value, stage: '服务端返回的检查项（本页未收录）' }))
  return [...GATE_CHECKS, ...extras]
})

const gateStatus = computed(() => {
  if (settingsLoading.value) return { label: '读取中…', tone: 'neutral' as const }
  if (!settingsCanManage.value) return { label: '只读', tone: 'neutral' as const }
  return gateView.value?.updatedBy
    ? { label: '已保存', tone: 'success' as const }
    : { label: '默认全开', tone: 'neutral' as const }
})

const gateSubtitle = computed(() => {
  if (settingsLoading.value) return '读取中…'
  if (!settingsCanManage.value) return '仅平台管理员可见'
  const view = gateView.value
  if (!view) return '未保存过，默认七项全开'
  const state = `${view.enabledChecks.length} / ${GATE_CHECKS.length} 项启用`
  return view.updatedBy
    ? `${state} · ${view.updatedBy} 更新于 ${formatDateTime(view.updatedAt)}`
    : `${state} · 默认配置`
})

const approverStatus = computed(() => {
  if (settingsLoading.value) return { label: '读取中…', tone: 'neutral' as const }
  if (!settingsCanManage.value) return { label: '只读', tone: 'neutral' as const }
  return approverView.value?.updatedBy
    ? { label: '已保存', tone: 'success' as const }
    : { label: '默认名单', tone: 'neutral' as const }
})

const approverSubtitle = computed(() => {
  if (settingsLoading.value) return '读取中…'
  if (!settingsCanManage.value) return '仅平台管理员可见'
  const view = approverView.value
  if (!view) return '默认沿用角色成员'
  const state = `运营负责人 ${view.opsLead.length} 人 · 部门经理 ${view.deptManager.length} 人`
  return view.updatedBy
    ? `${state} · ${view.updatedBy} 更新于 ${formatDateTime(view.updatedAt)}`
    : `${state} · 默认沿用角色成员`
})

const lockStatus = computed(() => {
  if (settingsLoading.value) return { label: '读取中…', tone: 'neutral' as const }
  if (!settingsCanManage.value) return { label: '只读', tone: 'neutral' as const }
  return lockView.value?.updatedBy
    ? { label: '已保存', tone: 'success' as const }
    : { label: '默认值', tone: 'neutral' as const }
})

const lockSubtitle = computed(() => {
  if (settingsLoading.value) return '读取中…'
  if (!settingsCanManage.value) return '仅平台管理员可见'
  const view = lockView.value
  if (!view) return '默认 168 小时'
  const state = `暂停上限 ${view.pauseBoundHours} 小时`
  return view.updatedBy
    ? `${state} · ${view.updatedBy} 更新于 ${formatDateTime(view.updatedAt)}`
    : `${state} · 默认值`
})

const approvalStatus = computed(() => {
  if (settingsLoading.value) return { label: '读取中…', tone: 'neutral' as const }
  if (!settingsCanManage.value) return { label: '只读', tone: 'neutral' as const }
  const view = approvalView.value
  if (view?.enabled === false) return { label: '已关闭', tone: 'neutral' as const }
  return view?.updatedBy
    ? { label: '已保存', tone: 'success' as const }
    : { label: '默认开启', tone: 'neutral' as const }
})

const approvalSubtitle = computed(() => {
  if (settingsLoading.value) return '读取中…'
  if (!settingsCanManage.value) return '仅平台管理员可见'
  const view = approvalView.value
  if (!view) return '默认开启 · 提醒 12 小时 · 升级 24 小时'
  const state = `${view.enabled ? '已开启' : '已关闭'} · 提醒 ${view.remindHours} 小时 · 升级 ${view.escalateHours} 小时`
  return view.updatedBy
    ? `${state} · ${view.updatedBy} 更新于 ${formatDateTime(view.updatedAt)}`
    : `${state} · 默认配置`
})

/** Whole hours only; `null` for anything that is not a plain non-negative integer. */
function parseHours(raw: string): number | null {
  const text = raw.trim()
  if (!/^\d+$/.test(text)) return null
  const hours = Number(text)
  return Number.isSafeInteger(hours) ? hours : null
}

/**
 * The client-side guard of design §2: remind 1–72, escalate 2–168, escalate > remind. Every failing
 * rule contributes one sentence, so the block can explain exactly why 保存 is disabled instead of
 * only flashing a toast after the click.
 */
const approvalProblems = computed(() => {
  const problems: string[] = []
  const remind = parseHours(remindHours.value)
  const escalate = parseHours(escalateHours.value)
  if (remind === null || remind < 1 || remind > 72) {
    problems.push(`提醒阈值需为 1–72 之间的整数小时（当前 ${remindHours.value.trim() || '空'}）`)
  }
  if (escalate === null || escalate < 2 || escalate > 168) {
    problems.push(`升级阈值需为 2–168 之间的整数小时（当前 ${escalateHours.value.trim() || '空'}）`)
  }
  if (remind !== null && escalate !== null && escalate <= remind) {
    problems.push(`升级阈值必须大于提醒阈值（当前 ${escalate} ≤ ${remind}）`)
  }
  return problems
})

const approvalInvalid = computed(() => approvalProblems.value.length > 0)

const approvalReason = computed(() =>
  approvalProblems.value.length
    ? `当前设置不可保存：${approvalProblems.value.join('；')}`
    : ''
)

function fillGate(view: GateSettingView) {
  gateView.value = view
  gateEnabled.value = [...view.enabledChecks]
}

function fillApprovers(view: ApproverSettingView) {
  approverView.value = view
  opsLead.value = [...view.opsLead]
  deptManager.value = [...view.deptManager]
  opsLeadDraft.value = ''
  deptManagerDraft.value = ''
}

function fillLock(view: LockSettingView) {
  lockView.value = view
  pauseHours.value = String(view.pauseBoundHours)
}

function fillApproval(view: ApprovalSettingView) {
  approvalView.value = view
  approvalEnabled.value = view.enabled
  remindHours.value = String(view.remindHours)
  escalateHours.value = String(view.escalateHours)
}

async function loadPlatformSettings() {
  settingsLoading.value = true
  let me
  try {
    me = await getMe()
  } catch (e) {
    const message = errorMessage(e, '读取当前账号权限失败')
    gateError.value = message
    approverError.value = message
    lockError.value = message
    approvalError.value = message
    waiverError.value = message
    settingsLoading.value = false
    return
  }
  settingsCanManage.value = me.perms.includes('setting:manage')
  if (!settingsCanManage.value) {
    // No permission means no read either: the endpoints answer 403, so do not call them.
    settingsLoading.value = false
    return
  }
  const [gate, approvers, lock, approval, waiver] = await Promise.allSettled([
    getGateSetting(),
    getApproverSetting(),
    getLockSetting(),
    getApprovalSetting(),
    getWaiverSetting()
  ])
  if (gate.status === 'fulfilled') fillGate(gate.value)
  else gateError.value = errorMessage(gate.reason, '读取门禁规则失败')
  if (approvers.status === 'fulfilled') fillApprovers(approvers.value)
  else approverError.value = errorMessage(approvers.reason, '读取审批人名单失败')
  if (lock.status === 'fulfilled') fillLock(lock.value)
  else lockError.value = errorMessage(lock.reason, '读取锁超时配置失败')
  if (approval.status === 'fulfilled') fillApproval(approval.value)
  else approvalError.value = errorMessage(approval.reason, '读取审批时效配置失败')
  if (waiver.status === 'fulfilled') await fillWaiver(waiver.value)
  else waiverError.value = errorMessage(waiver.reason, '读取豁免默认有效期失败')
  settingsLoading.value = false
}

function gateLabel(value: string): string {
  return GATE_CHECKS.find((check) => check.value === value)?.label ?? value
}

async function saveGate() {
  if (!gateEnabled.value.length) {
    ElMessage.warning('至少需要启用一项检查')
    return
  }
  const removed = (gateView.value?.enabledChecks ?? []).filter(
    (check) => !gateEnabled.value.includes(check)
  )
  if (removed.length) {
    try {
      await ElMessageBox.confirm(
        `即将停用 ${removed.map(gateLabel).join('、')}。停用对在途发布立即生效，且不会补跑：` +
          '已停用检查的历史结果在评估时被忽略，新实例也不再生成这些检查任务。',
        '保存门禁规则',
        { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  gateSaving.value = true
  try {
    const view = await saveGateSetting({ enabledChecks: [...gateEnabled.value] })
    fillGate(view)
    ElMessage.success('门禁规则已保存')
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存门禁规则失败'))
  } finally {
    gateSaving.value = false
  }
}

type ApproverRole = 'opsLead' | 'deptManager'

function roleList(role: ApproverRole) {
  return role === 'opsLead' ? opsLead : deptManager
}

function roleDraft(role: ApproverRole) {
  return role === 'opsLead' ? opsLeadDraft : deptManagerDraft
}

function addApprover(role: ApproverRole) {
  const draft = roleDraft(role)
  const userId = draft.value.trim()
  if (!userId) {
    ElMessage.warning('请先填写工号')
    return
  }
  const list = roleList(role)
  if (list.value.includes(userId)) {
    draft.value = ''
    ElMessage.warning(`${userId} 已在名单中`)
    return
  }
  list.value = [...list.value, userId]
  draft.value = ''
}

function removeApprover(role: ApproverRole, userId: string) {
  const list = roleList(role)
  list.value = list.value.filter((id) => id !== userId)
}

/** Trims and de-duplicates; the backend applies the same rule, this only keeps the form tidy. */
function normalizeApprovers(ids: string[]): string[] {
  return [...new Set(ids.map((id) => id.trim()).filter(Boolean))]
}

async function saveApprovers() {
  // A value typed but not yet added is committed by this save; the tag then shows what is sent.
  for (const role of ['opsLead', 'deptManager'] as const) {
    if (roleDraft(role).value.trim()) addApprover(role)
  }
  approverSaving.value = true
  try {
    const view = await saveApproverSetting({
      opsLead: normalizeApprovers(opsLead.value),
      deptManager: normalizeApprovers(deptManager.value)
    })
    fillApprovers(view)
    ElMessage.success('审批人名单已保存')
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存审批人名单失败'))
  } finally {
    approverSaving.value = false
  }
}

async function saveLock() {
  const raw = pauseHours.value.trim()
  const hours = Number(raw)
  if (!raw || !Number.isInteger(hours) || hours < 1 || hours > 8760) {
    ElMessage.warning('暂停上限需为 1–8760 之间的整数小时')
    return
  }
  lockSaving.value = true
  try {
    const view = await saveLockSetting({ pauseBoundHours: hours })
    fillLock(view)
    ElMessage.success('锁超时设置已保存')
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存锁超时设置失败'))
  } finally {
    lockSaving.value = false
  }
}

async function saveApproval() {
  if (approvalInvalid.value) {
    ElMessage.warning(approvalProblems.value[0] ?? '审批时效设置不合法')
    return
  }
  const remind = parseHours(remindHours.value)
  const escalate = parseHours(escalateHours.value)
  if (remind === null || escalate === null) {
    // Unreachable while approvalInvalid is false; keeps the request body typed.
    ElMessage.warning('提醒阈值与升级阈值都必须是整数小时')
    return
  }
  approvalSaving.value = true
  try {
    const view = await saveApprovalSetting({
      enabled: approvalEnabled.value,
      remindHours: remind,
      escalateHours: escalate
    })
    fillApproval(view)
    ElMessage.success('审批时效设置已保存')
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存审批时效设置失败'))
  } finally {
    approvalSaving.value = false
  }
}

onMounted(() => {
  checkBackend()
  loadConn()
  loadNotify()
  loadPlatformSettings()
})
</script>

<template>
  <div>
    <PageHeader title="设置" description="本地偏好、身份、平台规则，以及 CODING 对接与消息通知配置。" />

    <DataSection
      title="CODING 对接"
      :subtitle="connLoading ? '读取中…' : configured ? `已配置 · 更新于 ${formatDateTime(conn?.updatedAt)}` : '未配置'"
      style="margin-bottom: 16px"
    >
      <template #actions>
        <span class="chip" :class="`chip-${connStatus.tone}`">{{ connStatus.label }}</span>
        <template v-if="canManage">
          <el-button :disabled="testing" :loading="testing" @click="testConn">
            {{ testing ? '测试中…' : '测试连接' }}
          </el-button>
          <el-button type="primary" :disabled="saving" :loading="saving" @click="saveConn">
            保存
          </el-button>
        </template>
      </template>

      <el-alert
        v-if="connError"
        type="error"
        :closable="false"
        show-icon
        :title="connError"
        style="margin-bottom: 12px"
      />

      <div v-if="!canManage" class="restricted">
        <span class="strong">需要平台管理员权限</span>
        <span class="weak small">
          CODING 连接是全平台共用的一条租户连接，只有具备 <code class="mono">setting:manage</code>
          的账号可以查看和修改。当前状态：{{ configured ? '已配置' : '未配置' }}。
        </span>
      </div>

      <template v-else>
        <div class="conn-grid">
          <label class="field span-2">
            <span class="field-label">服务地址</span>
            <el-input v-model="form.baseUrl" placeholder="https://coding.example.com" spellcheck="false" />
          </label>
          <label class="field">
            <span class="field-label">团队</span>
            <el-input v-model="form.team" placeholder="团队标识" spellcheck="false" />
          </label>
          <label class="field">
            <span class="field-label">默认项目</span>
            <el-input v-model="form.project" placeholder="项目标识" spellcheck="false" />
          </label>
          <label class="field span-2">
            <span class="field-label">
              访问凭证
              <span v-if="configured" class="weak">（已保存 {{ conn?.tokenHint }}，留空不修改）</span>
            </span>
            <el-input
              v-model="form.token"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="configured ? '留空保留原凭证，填写则轮换' : '首次配置必须填写'"
            />
          </label>
          <div class="field span-2 hint">
            <span>凭证 AES-GCM 加密落库，接口只回显前四位；明文不落库、不进日志。</span>
            <span>子系统未填「CODING 项目」时，任务查询使用这里的默认项目。</span>
            <span>
              端点路径与状态词表在 <code class="mono">coding-endpoints.yml</code>，改动需重启生效。
            </span>
          </div>
        </div>

        <div v-if="probe || conn?.lastProbeAt" class="probe" :class="probeTone">
          <div class="probe-head">
            <span class="probe-title">
              {{ probe ? (probe.ok ? '连接可用' : '连接不可用') : conn?.lastProbeOk ? '上次校验可用' : '上次校验失败' }}
            </span>
            <span class="weak small">{{ formatDateTime(probe?.at ?? conn?.lastProbeAt) }}</span>
          </div>
          <p class="probe-message">{{ probe?.message ?? conn?.lastProbeMessage ?? '—' }}</p>
          <div v-if="probe?.ok" class="probe-counts">
            <span class="count"><b>{{ probe.buildJobs }}</b> 个构建任务</span>
            <span class="count"><b>{{ probe.deployJobs }}</b> 个部署任务</span>
          </div>
          <p v-if="probe" class="weak small" style="margin: 6px 0 0">
            {{ probe.persisted ? '已记录为上次校验结果。' : '草稿测试：结果不写入配置。' }}
          </p>
        </div>
      </template>
    </DataSection>

    <DataSection
      class="settings-notify"
      title="消息通知"
      :subtitle="notifySubtitle"
      style="margin-bottom: 16px"
    >
      <template #actions>
        <span class="chip" :class="`chip-${notifyStatus.tone}`">{{ notifyStatus.label }}</span>
        <el-button
          v-if="notifyCanManage"
          type="primary"
          :disabled="notifySaving"
          :loading="notifySaving"
          @click="saveNotify"
        >
          保存
        </el-button>
      </template>

      <el-alert
        v-if="notifyError"
        type="error"
        :closable="false"
        show-icon
        :title="notifyError"
        style="margin-bottom: 12px"
      />

      <div v-if="!notifyCanManage" class="restricted">
        <span class="strong">需要平台管理员权限</span>
        <span class="weak small">
          企微机器人是全平台共用的一条配置，只有具备 <code class="mono">setting:manage</code>
          的账号可以查看和修改；当前账号无权读取该配置。站内通知始终开启，无需配置。
        </span>
      </div>

      <template v-else>
        <div class="notify-grid">
          <label class="field">
            <span class="field-label">企微机器人通知</span>
            <span class="switch-row">
              <el-switch
                v-model="notifyEnabled"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="weak small">关闭时只发站内通知，不创建企微发送任务。</span>
            </span>
          </label>
          <label class="field">
            <span class="field-label">机器人 Webhook</span>
            <el-input
              v-model="notifyWebhook"
              placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."
              spellcheck="false"
            />
          </label>
          <div class="field hint">
            <span>
              机器人地址内嵌密钥，属于敏感配置：接口只对具备
              <code class="mono">setting:manage</code> 的账号原样回显，请勿转发或截图外传。
            </span>
            <span>开启时必须填写 <code class="mono">https://</code> 地址；发送失败会记录在该条通知上，不阻塞发版流程。</span>
            <span>站内通知始终开启，无需配置。</span>
          </div>
        </div>
        <p v-if="notifyView?.updatedBy" class="weak small notify-audit">
          最近保存：{{ notifyView.updatedBy }} · {{ formatDateTime(notifyView.updatedAt) }}
        </p>
      </template>
    </DataSection>

    <DataSection
      class="settings-gate"
      title="门禁规则"
      :subtitle="gateSubtitle"
      style="margin-bottom: 16px"
    >
      <template #actions>
        <span class="chip" :class="`chip-${gateStatus.tone}`">{{ gateStatus.label }}</span>
        <el-button
          v-if="settingsCanManage"
          type="primary"
          class="gate-save"
          :disabled="gateSaving"
          :loading="gateSaving"
          @click="saveGate"
        >
          保存
        </el-button>
      </template>

      <el-alert
        v-if="gateError"
        type="error"
        :closable="false"
        show-icon
        :title="gateError"
        style="margin-bottom: 12px"
      />

      <div v-if="!settingsLoading && !settingsCanManage" class="restricted">
        <span class="strong">需要平台管理员权限</span>
        <span class="weak small">
          门禁规则决定每个发布实例执行哪些检查，只有具备 <code class="mono">setting:manage</code>
          的账号可以查看和修改；当前账号无权读取该配置。
        </span>
      </div>

      <template v-else-if="settingsCanManage">
        <el-checkbox-group v-model="gateEnabled" class="gate-checks">
          <el-checkbox
            v-for="check in gateChecks"
            :key="check.value"
            :value="check.value"
            class="gate-check"
          >
            <span class="check-label">{{ check.label }}</span>
            <code class="mono check-code">{{ check.value }}</code>
            <span class="weak small check-note">{{ check.stage }}</span>
          </el-checkbox>
        </el-checkbox-group>
        <div class="field hint gate-hint">
          <span>
            停用对在途发布立即生效，且不会补跑：已停用检查的历史结果在评估时被忽略，
            新实例也不再生成该检查任务。
          </span>
          <span>至少保留 1 项检查；当前启用 <b>{{ gateEnabled.length }}</b> / {{ gateTotal }} 项。</span>
        </div>
        <div class="waiver-days">
          <label class="field">
            <span class="field-label">豁免默认有效期</span>
            <div class="lock-row">
              <el-input
                v-model="waiverDays"
                class="waiver-days-input"
                inputmode="numeric"
                placeholder="7"
                style="max-width: 200px"
              >
                <template #append>天</template>
              </el-input>
              <el-button
                type="primary"
                class="waiver-save"
                :disabled="!!waiverInvalid || waiverSaving"
                :loading="waiverSaving"
                @click="saveWaiver"
              >
                保存
              </el-button>
              <span class="weak small waiver-days-hint">
                申请豁免时有效期留空即按此天数（默认 7 天，可填 1–90 的整数；显式填写的上限仍是 90 天）。
              </span>
            </div>
          </label>
          <p v-if="waiverView?.updatedBy" class="weak small waiver-audit">
            最近保存：{{ waiverView.updatedBy }} · {{ formatDateTime(waiverView.updatedAt) }}
          </p>
        </div>
      </template>
    </DataSection>

    <DataSection
      class="settings-approvers"
      title="审批人名单"
      :subtitle="approverSubtitle"
      style="margin-bottom: 16px"
    >
      <template #actions>
        <span class="chip" :class="`chip-${approverStatus.tone}`">{{ approverStatus.label }}</span>
        <el-button
          v-if="settingsCanManage"
          type="primary"
          class="approver-save"
          :disabled="approverSaving"
          :loading="approverSaving"
          @click="saveApprovers"
        >
          保存
        </el-button>
      </template>

      <el-alert
        v-if="approverError"
        type="error"
        :closable="false"
        show-icon
        :title="approverError"
        style="margin-bottom: 12px"
      />

      <div v-if="!settingsLoading && !settingsCanManage" class="restricted">
        <span class="strong">需要平台管理员权限</span>
        <span class="weak small">
          审批人名单决定生产发布由谁审批，只有具备 <code class="mono">setting:manage</code>
          的账号可以查看和修改；当前账号无权读取该配置。
        </span>
      </div>

      <template v-else-if="settingsCanManage">
        <div class="approver-grid">
          <div class="field approver-role" data-role="opsLead">
            <span class="field-label">
              运营负责人 <code class="mono">OPS_LEAD</code>
            </span>
            <div class="tag-row">
              <el-tag
                v-for="id in opsLead"
                :key="id"
                class="approver-tag"
                closable
                @close="removeApprover('opsLead', id)"
              >
                {{ id }}
              </el-tag>
              <span v-if="!opsLead.length" class="weak small">未配置</span>
            </div>
            <div class="tag-input">
              <el-input
                v-model="opsLeadDraft"
                class="approver-input"
                placeholder="输入工号后回车，或点「添加」"
                spellcheck="false"
                @keyup.enter="addApprover('opsLead')"
              />
              <el-button class="approver-add" @click="addApprover('opsLead')">添加</el-button>
            </div>
          </div>

          <div class="field approver-role" data-role="deptManager">
            <span class="field-label">
              部门经理 <code class="mono">DEPT_MANAGER</code>
            </span>
            <div class="tag-row">
              <el-tag
                v-for="id in deptManager"
                :key="id"
                class="approver-tag"
                closable
                @close="removeApprover('deptManager', id)"
              >
                {{ id }}
              </el-tag>
              <span v-if="!deptManager.length" class="weak small">未配置</span>
            </div>
            <div class="tag-input">
              <el-input
                v-model="deptManagerDraft"
                class="approver-input"
                placeholder="输入工号后回车，或点「添加」"
                spellcheck="false"
                @keyup.enter="addApprover('deptManager')"
              />
              <el-button class="approver-add" @click="addApprover('deptManager')">添加</el-button>
            </div>
          </div>
        </div>

        <div class="field hint approver-hint">
          <span>留空 = 由系统负责人兜底（该角色无人时沿用既有审批兜底），不会阻断发版。</span>
          <span>
            保存会把 <code class="mono">user_role</code> 中该角色的成员整体替换为名单，审批权限即时生效。
            接口回显的是生效名单：存储名单为空时回退显示角色当前成员，因此保存空名单后通常回显也为空。
          </span>
          <span>工号需存在于 <code class="mono">app_user</code> 且为启用状态；提交前自动去除首尾空格并去重。</span>
        </div>
      </template>
    </DataSection>

    <DataSection
      class="settings-lock"
      title="锁超时"
      :subtitle="lockSubtitle"
      style="margin-bottom: 16px"
    >
      <template #actions>
        <span class="chip" :class="`chip-${lockStatus.tone}`">{{ lockStatus.label }}</span>
        <el-button
          v-if="settingsCanManage"
          type="primary"
          class="lock-save"
          :disabled="lockSaving"
          :loading="lockSaving"
          @click="saveLock"
        >
          保存
        </el-button>
      </template>

      <el-alert
        v-if="lockError"
        type="error"
        :closable="false"
        show-icon
        :title="lockError"
        style="margin-bottom: 12px"
      />

      <div v-if="!settingsLoading && !settingsCanManage" class="restricted">
        <span class="strong">需要平台管理员权限</span>
        <span class="weak small">
          暂停上限直接影响发布单元的租约回收，只有具备 <code class="mono">setting:manage</code>
          的账号可以查看和修改；当前账号无权读取该配置。
        </span>
      </div>

      <template v-else-if="settingsCanManage">
        <div class="lock-grid">
          <label class="field">
            <span class="field-label">暂停上限</span>
            <div class="lock-row">
              <el-input
                v-model="pauseHours"
                class="lock-hours"
                inputmode="numeric"
                placeholder="168"
                style="max-width: 200px"
              >
                <template #append>小时</template>
              </el-input>
              <span class="weak small">默认 168 小时（7 天），可填 1–8760 的整数。</span>
            </div>
          </label>
          <div class="field hint">
            <span>发布挂起超过该小时数视为放弃，单元租约由巡检任务在下一轮释放；修改在下一轮巡检生效。</span>
            <span>租约本身仍为 <b>4 小时</b>（TTL 是代码常量，不随此项变化）。</span>
          </div>
        </div>
      </template>
    </DataSection>

    <DataSection
      class="settings-approval"
      title="审批时效"
      :subtitle="approvalSubtitle"
      style="margin-bottom: 16px"
    >
      <template #actions>
        <span class="chip" :class="`chip-${approvalStatus.tone}`">{{ approvalStatus.label }}</span>
        <el-button
          v-if="settingsCanManage"
          type="primary"
          class="approval-save"
          :disabled="approvalSaving || approvalInvalid"
          :loading="approvalSaving"
          @click="saveApproval"
        >
          保存
        </el-button>
      </template>

      <el-alert
        v-if="approvalError"
        type="error"
        :closable="false"
        show-icon
        :title="approvalError"
        style="margin-bottom: 12px"
      />

      <div v-if="!settingsLoading && !settingsCanManage" class="restricted">
        <span class="strong">需要平台管理员权限</span>
        <span class="weak small">
          审批时效决定待签审批的提醒与升级，只有具备 <code class="mono">setting:manage</code>
          的账号可以查看和修改；当前账号无权读取该配置。
        </span>
      </div>

      <template v-else-if="settingsCanManage">
        <div class="approval-grid">
          <label class="field">
            <span class="field-label">超时提醒</span>
            <span class="switch-row">
              <el-switch
                v-model="approvalEnabled"
                class="approval-enabled"
                active-text="开启"
                inactive-text="关闭"
              />
              <span class="weak small">关闭后扫描不发任何提醒与升级消息。</span>
            </span>
          </label>
          <label class="field">
            <span class="field-label">提醒阈值</span>
            <div class="approval-row">
              <el-input
                v-model="remindHours"
                class="approval-hours approval-remind"
                inputmode="numeric"
                placeholder="12"
                style="max-width: 200px"
              >
                <template #append>小时</template>
              </el-input>
              <span class="weak small">待签满该小时数发提醒；默认 12，可填 1–72 的整数。</span>
            </div>
          </label>
          <label class="field">
            <span class="field-label">升级阈值</span>
            <div class="approval-row">
              <el-input
                v-model="escalateHours"
                class="approval-hours approval-escalate"
                inputmode="numeric"
                placeholder="24"
                style="max-width: 200px"
              >
                <template #append>小时</template>
              </el-input>
              <span class="weak small">
                待签满该小时数升级；默认 24，可填 2–168 的整数，且必须大于提醒阈值。
              </span>
            </div>
          </label>
        </div>

        <el-alert
          v-if="approvalInvalid"
          class="approval-invalid"
          type="warning"
          :closable="false"
          show-icon
          :title="approvalReason"
          style="margin-top: 12px"
        />

        <div class="field hint approval-hint">
          <span>
            提醒发给当前待签审批人；升级发给 ADMIN 与系统负责人；升级后不再提醒。
          </span>
          <span>
            扫描每 10 分钟一轮，只扫最新一代的待签步骤：同一代同一步骤只提醒一次、只升级一次，
            发布单已终结或已离开待审批后不再发消息。
          </span>
          <span>保存即时生效，无需重启；阈值修改会记入审计（<code class="mono">SETTING_UPDATE</code>）。</span>
        </div>

        <p v-if="approvalView?.updatedBy" class="weak small approval-audit">
          最近保存：{{ approvalView.updatedBy }} · {{ formatDateTime(approvalView.updatedAt) }}
        </p>
      </template>
    </DataSection>

    <div class="grid">
      <div class="stack">
        <DataSection title="外观">
          <el-radio-group :model-value="theme" @change="onTheme($event as Theme)">
            <el-radio-button label="light">浅色</el-radio-button>
            <el-radio-button label="dark">深色</el-radio-button>
          </el-radio-group>
          <p class="weak small" style="margin: 10px 0 0">
            默认跟随系统；手动切换后会记住选择（<code class="mono">localStorage.rp.theme</code>）。
          </p>
        </DataSection>

        <DataSection title="身份">
          <el-input v-model="userId" placeholder="工号，例如 dev" style="max-width: 260px" />
          <div class="row" style="margin-top: 10px">
            <el-button type="primary" @click="saveUser">保存</el-button>
            <span class="weak small">请求头 X-User-Id；需存在于 app_user 且有角色。</span>
          </div>
        </DataSection>
      </div>

      <div class="stack">
        <DataSection title="后端连通性">
          <template #actions>
            <el-button :loading="checking" @click="checkBackend">重新检测</el-button>
          </template>
          <KeyValue label="开发代理" value="Vite → 127.0.0.1:8080" mono />
          <KeyValue label="状态">
            <span v-if="backend" :style="{ color: backend.ok ? 'var(--accent)' : 'var(--danger)' }">
              {{ backend.message }}
            </span>
            <span v-else class="weak">检测中…</span>
          </KeyValue>
          <KeyValue v-if="backend?.ok" label="系统数" :value="backend.subsystems" mono />
        </DataSection>

        <DataSection title="运行时">
          <KeyValue label="Webhook" value="POST /api/v1/hooks/coding（HMAC-SHA256）" mono />
          <KeyValue label="门禁 / 验证确认" value="P2 已交付" />
          <KeyValue label="生产管控" value="P3（审批 / 窗口 / 封板）" />
        </DataSection>
      </div>
    </div>
  </div>
</template>

<style scoped>
.conn-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px 18px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  color: var(--text-2);
}

.span-2 {
  grid-column: span 2;
}

.hint {
  gap: 4px;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.7;
  padding-top: 2px;
}

.restricted {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-2);
  background: var(--surface-3);
}

.chip-success {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  background: var(--accent-weak);
}

.chip-danger {
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 35%, var(--border));
  background: var(--danger-weak);
}

.chip-warn {
  color: var(--warn);
  border-color: color-mix(in srgb, var(--warn) 35%, var(--border));
  background: var(--warn-weak);
}

.probe {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--text-3);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.probe.is-ok {
  border-left-color: var(--accent);
  background: var(--accent-weak);
}

.probe.is-fail {
  border-left-color: var(--danger);
  background: var(--danger-weak);
}

.probe-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.probe-title {
  font-weight: 600;
}

.probe-message {
  margin: 6px 0 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.probe-counts {
  display: flex;
  gap: 18px;
  margin-top: 8px;
  color: var(--text-2);
  font-size: 12px;
}

.probe-counts b {
  color: var(--text);
  font-size: 15px;
  font-weight: 600;
  margin-right: 2px;
}

/* 「消息通知」 block: same field grid as CODING, one column so the webhook gets the full width. */
.notify-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  max-width: 760px;
}

.switch-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.notify-audit {
  margin: 12px 0 0;
}

/* 「门禁规则」 block: seven checkboxes in two columns; each row shows label + machine value. */
.gate-checks {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 22px;
  max-width: 940px;
}

.gate-check {
  height: auto;
  min-height: 34px;
  align-items: flex-start;
  margin-right: 0;
  padding: 5px 0;
}

.gate-check :deep(.el-checkbox__label) {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 8px;
  line-height: 1.45;
}

.check-label {
  font-size: 13px;
  color: var(--text);
}

.check-code {
  font-size: 11px;
  color: var(--text-3);
}

.check-note {
  font-size: 11px;
  color: var(--text-3);
}

.gate-hint {
  margin-top: 10px;
}

/* 「豁免默认有效期」 field inside the gate block (P6-F PART-06). */
.waiver-days {
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px dashed var(--border);
}
.waiver-days .lock-row {
  flex-wrap: wrap;
}
.waiver-audit {
  margin: 8px 0 0;
}

/* 「审批人名单」 block: one tag editor per approval role. */
.approver-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 18px;
  max-width: 940px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-height: 26px;
}

.tag-input {
  display: flex;
  align-items: center;
  gap: 8px;
}

.approver-input {
  max-width: 280px;
}

.approver-hint {
  margin-top: 12px;
}

/* 「锁超时」 block: the number field plus the fixed-lease reminder. */
.lock-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  max-width: 760px;
}

.lock-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

/* 「审批时效」 block (P6-E): the switch plus the two threshold fields. */
.approval-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  max-width: 760px;
}

.approval-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.approval-hint {
  margin-top: 12px;
}

.approval-audit {
  margin: 12px 0 0;
}

.grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

@media (max-width: 960px) {
  .grid,
  .conn-grid,
  .gate-checks,
  .approver-grid {
    grid-template-columns: 1fr !important;
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>