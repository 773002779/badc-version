<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  checkWindow,
  createFreeze,
  getMe,
  getWindow,
  listFreezes,
  listSubsystems,
  releaseFreeze,
  saveWindow
} from '../api'
import type { FreezeView, SubsystemView, WindowCheckView, WindowView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import KeyValue from '../components/KeyValue.vue'
import { formatDateTime } from '../utils/format'

const tab = ref<'freezes' | 'windows'>('freezes')
const me = ref<{ perms: string[]; roles: string[]; userId: string } | null>(null)
const subsystems = ref<SubsystemView[]>([])
const error = ref<string | null>(null)

const canFreeze = computed(() => has('control:freeze'))
const canUnfreeze = computed(() => has('control:unfreeze'))
const canManage = computed(() => has('setting:manage'))

function has(perm: string) {
  return !!me.value?.perms.includes(perm)
}

// ------------------------------------------------------------------ freezes
const freezes = ref<FreezeView[]>([])
const freezeLoading = ref(false)
const freezeDialog = ref(false)
const savingFreeze = ref(false)

/**
 * The page lists every ACTIVE freeze, not only the ones in force: a scheduled freeze is recorded
 * immediately (`FreezeService.create`), and an admin who mis-set its start time must still be able
 * to lift it (independent review M3). The history rows are shown last, capped, so the list stays
 * readable.
 */
const inForceFreezes = computed(() => freezes.value.filter((f) => f.status === 'ACTIVE' && f.active))
const scheduledFreezes = computed(
  () => freezes.value.filter((f) => f.status === 'ACTIVE' && !f.active)
)
const historyFreezes = computed(() => freezes.value.filter((f) => f.status !== 'ACTIVE'))
const recentHistory = computed(() => historyFreezes.value.slice(0, 10))

const freezeForm = reactive({
  scope: 'GLOBAL',
  subsystemId: null as number | null,
  reason: '',
  impact: '',
  startAt: null as Date | null,
  endAt: null as Date | null
})

async function loadFreezes() {
  freezeLoading.value = true
  try {
    // active=false is the history/newest-first list; it includes the ACTIVE rows, so one call
    // feeds all three sections below.
    freezes.value = await listFreezes({ active: false })
    error.value = null
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '读取封板失败'
  } finally {
    freezeLoading.value = false
  }
}

function openFreeze() {
  freezeForm.scope = 'GLOBAL'
  freezeForm.subsystemId = null
  freezeForm.reason = ''
  freezeForm.impact = ''
  freezeForm.startAt = new Date()
  freezeForm.endAt = null
  freezeDialog.value = true
}

async function submitFreeze() {
  if (!freezeForm.reason.trim()) {
    ElMessage.warning('事由必填')
    return
  }
  if (!freezeForm.endAt) {
    ElMessage.warning('请选择结束时间')
    return
  }
  if (freezeForm.scope === 'SUBSYSTEM' && !freezeForm.subsystemId) {
    ElMessage.warning('请选择系统')
    return
  }
  savingFreeze.value = true
  try {
    await createFreeze({
      scope: freezeForm.scope,
      subsystemId: freezeForm.scope === 'SUBSYSTEM' ? freezeForm.subsystemId : null,
      reason: freezeForm.reason.trim(),
      impact: freezeForm.impact.trim() || undefined,
      startAt: freezeForm.startAt ? freezeForm.startAt.toISOString() : null,
      endAt: freezeForm.endAt.toISOString()
    })
    freezeDialog.value = false
    ElMessage.success('封板已生效')
    await loadFreezes()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '封板失败')
  } finally {
    savingFreeze.value = false
  }
}

async function doUnfreeze(freeze: FreezeView) {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      `解除封板「${freeze.reason}」。解除后受影响发布单会重算审批链（已完成的会签保留）。`,
      '解除封板',
      {
        confirmButtonText: '解除',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputValidator: (v: string) => (v && v.trim() ? true : '必须填写解除原因')
      }
    )
    reason = (result.value ?? '').trim()
  } catch {
    return
  }
  try {
    await releaseFreeze(freeze.id, reason)
    ElMessage.success('封板已解除')
    await loadFreezes()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '解除失败')
  }
}

// ------------------------------------------------------------------ window
const targetSubsystem = ref<number | null>(null)
const windowView = ref<WindowView | null>(null)
const windowLoading = ref(false)
const windowSaving = ref(false)
const windowDraft = reactive({
  scope: 'GLOBAL',
  enabled: true,
  timezone: 'Asia/Shanghai',
  rules: [] as Array<{ dayOfWeek: number; start: string; end: string }>,
  exceptions: [] as Array<{ date: string; mode: string; start?: string | null; end?: string | null }>
})
const checkAt = ref<Date>(new Date())
const checkResult = ref<WindowCheckView | null>(null)
const warnings = ref<string[]>([])

const DAYS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' }
]

function dayLabel(day: number) {
  return DAYS.find((d) => d.value === day)?.label ?? `Day ${day}`
}

async function loadWindow() {
  windowLoading.value = true
  try {
    const view = await getWindow(targetSubsystem.value ?? undefined)
    windowView.value = view
    windowDraft.scope = targetSubsystem.value ? 'SUBSYSTEM' : 'GLOBAL'
    windowDraft.enabled = view.configured ? view.enabled : true
    windowDraft.timezone = view.timezone ?? 'Asia/Shanghai'
    windowDraft.rules = view.rules.map((r) => ({
      dayOfWeek: r.dayOfWeek,
      start: r.start.slice(0, 5),
      end: r.end.slice(0, 5)
    }))
    windowDraft.exceptions = view.exceptions.map((x) => ({
      date: x.date,
      mode: x.mode,
      start: x.start ? x.start.slice(0, 5) : null,
      end: x.end ? x.end.slice(0, 5) : null
    }))
    warnings.value = []
    await doCheck()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '读取发布窗口失败'
  } finally {
    windowLoading.value = false
  }
}

async function doCheck() {
  try {
    checkResult.value = await checkWindow({
      subsystemId: targetSubsystem.value ?? undefined,
      at: checkAt.value ? checkAt.value.toISOString() : null
    })
  } catch (e) {
    checkResult.value = null
    if (e instanceof ApiError) ElMessage.warning(e.message)
  }
}

function addRule() {
  windowDraft.rules.push({ dayOfWeek: 5, start: '22:00', end: '23:59' })
}

function addException() {
  const today = new Date().toISOString().slice(0, 10)
  windowDraft.exceptions.push({ date: today, mode: 'CLOSED', start: null, end: null })
}

async function saveWindowConfig() {
  if (!canManage.value) {
    ElMessage.warning('需要平台管理员权限（setting:manage）')
    return
  }
  windowSaving.value = true
  try {
    const view = await saveWindow({
      scope: windowDraft.scope,
      subsystemId: windowDraft.scope === 'SUBSYSTEM' ? targetSubsystem.value : null,
      enabled: windowDraft.enabled,
      timezone: windowDraft.timezone || 'Asia/Shanghai',
      rules: windowDraft.rules.map((r) => ({
        dayOfWeek: r.dayOfWeek,
        start: r.start.length === 5 ? `${r.start}:00` : r.start,
        end: r.end.length === 5 ? `${r.end}:00` : r.end
      })),
      exceptions: windowDraft.exceptions.map((x) => ({
        date: x.date,
        mode: x.mode,
        start: x.start ? (x.start.length === 5 ? `${x.start}:00` : x.start) : null,
        end: x.end ? (x.end.length === 5 ? `${x.end}:00` : x.end) : null
      }))
    })
    windowView.value = view
    warnings.value = view.warnings ?? []
    ElMessage.success(warnings.value.length ? `已保存，${warnings.value.length} 条提醒` : '发布窗口已保存')
    await doCheck()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    windowSaving.value = false
  }
}

function spanText(span?: { start: string; end: string } | null) {
  return span ? `${span.start.replace('T', ' ')} → ${span.end.replace('T', ' ')}` : '—'
}

onMounted(async () => {
  try {
    const [myself, page] = await Promise.all([getMe().catch(() => null), listSubsystems(1, 100)])
    if (myself) me.value = myself
    subsystems.value = page.records
  } catch {
    subsystems.value = []
  }
  await Promise.all([loadFreezes(), loadWindow()])
})
</script>

<template>
  <div>
    <PageHeader title="生产管控" description="发布窗口、封板，以及它们对生产准入的影响。" />

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <el-tabs v-model="tab">
      <el-tab-pane label="封板" name="freezes">
        <DataSection title="生效中的封板" :subtitle="`${inForceFreezes.length} 条`">
          <template #actions>
            <el-button :loading="freezeLoading" @click="loadFreezes">刷新</el-button>
            <el-button v-if="canFreeze" type="primary" @click="openFreeze">发起封板</el-button>
            <span v-else class="weak small">需要权限 control:freeze</span>
          </template>

          <div v-if="inForceFreezes.length" class="freeze-list">
            <div v-for="f in inForceFreezes" :key="f.id" class="freeze-row">
              <div class="freeze-main">
                <div class="row" style="gap: 8px">
                  <span class="chip chip-danger">封板中</span>
                  <span class="strong">{{ f.reason }}</span>
                </div>
                <div class="weak small freeze-meta">
                  范围 {{ f.scope === 'GLOBAL' ? '全局' : `系统 #${f.subsystemId}` }}
                  · {{ formatDateTime(f.startAt) }} → {{ formatDateTime(f.endAt) }}
                  · 发起人 {{ f.initiator }}
                </div>
                <div v-if="f.impact" class="weak small">影响：{{ f.impact }}</div>
              </div>
              <el-button v-if="canUnfreeze" :title="'解除封板'" @click="doUnfreeze(f)">解除</el-button>
              <span v-else class="weak small">需要 control:unfreeze</span>
            </div>
          </div>
          <EmptyState v-else-if="!freezeLoading" title="当前没有生效中的封板" hint="封板期内的生产发布需要运维组长 + 部门经理双签，构建与测试部署不受影响。" />
          <div v-else style="padding: 24px"><el-skeleton :rows="3" animated /></div>
        </DataSection>

        <DataSection
          v-if="scheduledFreezes.length"
          title="待生效的封板"
          :subtitle="`${scheduledFreezes.length} 条 · 已登记，到达开始时间后生效`"
        >
          <div class="freeze-list">
            <div v-for="f in scheduledFreezes" :key="f.id" class="freeze-row">
              <div class="freeze-main">
                <div class="row" style="gap: 8px">
                  <span class="chip chip-neutral">待生效</span>
                  <span class="strong">{{ f.reason }}</span>
                </div>
                <div class="weak small freeze-meta">
                  范围 {{ f.scope === 'GLOBAL' ? '全局' : `系统 #${f.subsystemId}` }}
                  · {{ formatDateTime(f.startAt) }} → {{ formatDateTime(f.endAt) }}
                  · 发起人 {{ f.initiator }}
                </div>
                <div v-if="f.impact" class="weak small">影响：{{ f.impact }}</div>
              </div>
              <el-button v-if="canUnfreeze" :title="'解除这条尚未生效的封板'" @click="doUnfreeze(f)">
                解除
              </el-button>
              <span v-else class="weak small">需要 control:unfreeze</span>
            </div>
          </div>
        </DataSection>

        <DataSection
          v-if="recentHistory.length"
          title="历史封板"
          :subtitle="`最近 ${recentHistory.length} 条 / 共 ${historyFreezes.length} 条`"
        >
          <div class="freeze-list">
            <div v-for="f in recentHistory" :key="f.id" class="freeze-row">
              <div class="freeze-main">
                <div class="row" style="gap: 8px">
                  <span class="chip chip-neutral">{{ f.statusLabel }}</span>
                  <span class="strong">{{ f.reason }}</span>
                </div>
                <div class="weak small freeze-meta">
                  范围 {{ f.scope === 'GLOBAL' ? '全局' : `系统 #${f.subsystemId}` }}
                  · {{ formatDateTime(f.startAt) }} → {{ formatDateTime(f.endAt) }}
                  · 发起人 {{ f.initiator }}
                  <template v-if="f.releasedBy"> · {{ f.releasedBy }} 于 {{ formatDateTime(f.releasedAt) }} 解除</template>
                </div>
              </div>
            </div>
          </div>
        </DataSection>
      </el-tab-pane>

      <el-tab-pane label="发布窗口" name="windows">
        <div class="grid-2">
          <DataSection title="窗口规则" :subtitle="windowView?.configured ? (windowView.inherited ? '继承全局默认' : '系统覆盖') : '未配置'">
            <template #actions>
              <el-select v-model="targetSubsystem" placeholder="全局默认" clearable style="width: 200px" @change="loadWindow">
                <el-option label="全局默认" :value="null" />
                <el-option v-for="s in subsystems" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
              <el-button :loading="windowLoading" @click="loadWindow">重新读取</el-button>
            </template>

            <div class="row" style="gap: 16px; margin-bottom: 10px">
              <span class="weak small">启用窗口限制</span>
              <el-switch v-model="windowDraft.enabled" />
              <span class="weak small">
                {{ windowDraft.enabled
                  ? '只有计划时间落在窗口内才免审批'
                  : '已关闭限制：提交即免审批（谨慎）' }}
              </span>
            </div>

            <div class="rule-head">
              <span class="weak small">星期规则</span>
              <el-button size="small" @click="addRule">加一条</el-button>
            </div>
            <div v-for="(r, i) in windowDraft.rules" :key="`r-${i}`" class="rule-row">
              <el-select v-model="r.dayOfWeek" style="width: 100px">
                <el-option v-for="d in DAYS" :key="d.value" :label="d.label" :value="d.value" />
              </el-select>
              <el-time-picker v-model="r.start" format="HH:mm" value-format="HH:mm" placeholder="开始" style="width: 110px" />
              <span class="weak">→</span>
              <el-time-picker v-model="r.end" format="HH:mm" value-format="HH:mm" placeholder="结束" style="width: 110px" />
              <span class="weak small">跨零点按区间判定</span>
              <el-button link type="danger" @click="windowDraft.rules.splice(i, 1)">删除</el-button>
            </div>
            <p v-if="!windowDraft.rules.length" class="weak small" style="margin: 6px 0 0">
              没有任何星期规则时，任何时间都不在窗口内（提交需要审批）。
            </p>

            <div class="rule-head" style="margin-top: 16px">
              <span class="weak small">日期例外</span>
              <el-button size="small" @click="addException">加一条</el-button>
            </div>
            <div v-for="(x, i) in windowDraft.exceptions" :key="`x-${i}`" class="rule-row">
              <el-date-picker v-model="x.date" type="date" value-format="YYYY-MM-DD" style="width: 140px" />
              <el-select v-model="x.mode" style="width: 110px">
                <el-option label="关闭整天" value="CLOSED" />
                <el-option label="临时开放" value="OPEN" />
              </el-select>
              <template v-if="x.mode === 'OPEN'">
                <el-time-picker v-model="x.start" format="HH:mm" value-format="HH:mm" placeholder="开始（可空）" style="width: 140px" />
                <el-time-picker v-model="x.end" format="HH:mm" value-format="HH:mm" placeholder="结束（可空）" style="width: 140px" />
              </template>
              <el-button link type="danger" @click="windowDraft.exceptions.splice(i, 1)">删除</el-button>
            </div>

            <el-alert
              v-for="(w, i) in warnings"
              :key="`w-${i}`"
              type="warning"
              :closable="false"
              :title="w"
              style="margin-top: 8px"
            />

            <div class="row" style="margin-top: 16px; gap: 10px">
              <el-button type="primary" :loading="windowSaving" :disabled="!canManage" :title="canManage ? '保存' : '需要设置权限'" @click="saveWindowConfig">
                保存窗口
              </el-button>
              <span v-if="!canManage" class="weak small">需要平台管理员权限（setting:manage）</span>
              <span class="weak small">时区 {{ windowDraft.timezone }}</span>
            </div>
          </DataSection>

          <div class="stack">
            <DataSection title="窗口试算">
              <el-date-picker v-model="checkAt" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" @change="doCheck" />
              <div v-if="checkResult" style="margin-top: 10px">
                <KeyValue label="判定">
                  <span :style="{ color: checkResult.inWindow ? 'var(--accent)' : 'var(--warn)' }">
                    {{ checkResult.inWindow ? '窗口内 · 免审批' : '窗口外 · 需运维组长审批' }}
                  </span>
                  <span v-if="!checkResult.configured" class="weak small">（未配置窗口：任何时间都在窗口外）</span>
                  <span v-else-if="!checkResult.enabled" class="weak small">（窗口限制已关闭：任何时间都在窗口内）</span>
                </KeyValue>
                <KeyValue label="所属区间" :value="spanText(checkResult.span)" mono />
                <KeyValue label="下一个窗口" :value="spanText(checkResult.next)" mono />
                <KeyValue label="规则来源" :value="checkResult.scope === 'GLOBAL' ? '全局默认' : `系统 #${targetSubsystem}`" />
              </div>
              <p class="weak small" style="margin: 10px 0 0">
                判定以开发提交时填写的计划生产时间为准；执行时只复核封板/豁免/镜像，不重算窗口。
              </p>
            </DataSection>

            <DataSection title="当前规则">
              <div v-for="r in windowDraft.rules" :key="`s-${r.dayOfWeek}-${r.start}`">
                <KeyValue :label="dayLabel(r.dayOfWeek)" :value="`${r.start} → ${r.end}`" mono />
              </div>
              <EmptyState v-if="!windowDraft.rules.length" title="没有星期规则" />
            </DataSection>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="freezeDialog" title="发起封板" width="560px">
      <p class="weak small" style="margin-top: 0">
        封板生效后，范围内所有生产发布转入双签（拦而不拒）；构建、测试环境与日常发布不受影响。
      </p>
      <el-form label-position="top">
        <el-form-item label="范围">
          <el-radio-group v-model="freezeForm.scope">
            <el-radio-button label="GLOBAL">全局</el-radio-button>
            <el-radio-button label="SUBSYSTEM">指定系统</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="freezeForm.scope === 'SUBSYSTEM'" label="系统">
          <el-select v-model="freezeForm.subsystemId" placeholder="选择系统" style="width: 100%">
            <el-option v-for="s in subsystems" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="事由" required>
          <el-input v-model="freezeForm.reason" maxlength="512" placeholder="例如 双十一大促封板" />
        </el-form-item>
        <el-form-item label="影响说明">
          <el-input v-model="freezeForm.impact" type="textarea" :rows="2" maxlength="1024" />
        </el-form-item>
        <div class="grid-2">
          <el-form-item label="开始时间">
            <el-date-picker v-model="freezeForm.startAt" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" />
          </el-form-item>
          <el-form-item label="结束时间" required>
            <el-date-picker v-model="freezeForm.endAt" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="freezeDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingFreeze" @click="submitFreeze">生效</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.freeze-list {
  display: flex;
  flex-direction: column;
}
.freeze-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px dashed var(--border);
}
.freeze-row:last-child {
  border-bottom: none;
}
.freeze-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.freeze-meta {
  padding-top: 2px;
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
.chip-danger {
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 35%, var(--border));
  background: var(--danger-weak);
}
.grid-2 {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: 16px;
}
.stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.rule-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0 6px;
}
.rule-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}
@media (max-width: 1100px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>