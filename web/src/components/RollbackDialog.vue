<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { executeRollback, getRollbackVersions } from '../api'
import type { RollbackResult, RollbackVersion, RollbackVersions } from '../api/types'
import { ApiError } from '../api/http'
import EmptyState from './EmptyState.vue'
import { formatDateTime } from '../utils/format'

const props = defineProps<{
  modelValue: boolean
  /** The deployment unit of the release; null only when the order has no unit. */
  unitId: number | null
  unitName?: string | null
  publishNo: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'changed'): void
}>()

const data = ref<RollbackVersions | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
/** The unit never deployed to production successfully (40001) or has no unit at all. */
const unavailable = ref(false)

const targetDigest = ref('')
const acknowledged = ref(false)
const reason = ref('')
const submitting = ref(false)
const result = ref<RollbackResult | null>(null)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const target = computed<RollbackVersion | null>(
  () => data.value?.history.find((v) => v.digest === targetDigest.value) ?? null
)

const canConfirm = computed(
  () =>
    !!data.value &&
    !!target.value &&
    acknowledged.value &&
    reason.value.trim().length > 0 &&
    !submitting.value
)

/** Why the confirm button is disabled; short enough for the footer line. */
const confirmBlock = computed(() => {
  if (unavailable.value) return '没有可回滚的版本'
  if (!data.value) return loading.value ? '正在读取版本清单' : ''
  if (!data.value.history.length) return '没有可回滚的历史版本'
  if (!target.value) return '请选择要回滚到的历史版本'
  if (!acknowledged.value) return '请先勾选「我已了解」'
  if (!reason.value.trim()) return '请填写回滚原因'
  return ''
})

function shortDigest(digest: string): string {
  if (!digest) return '—'
  const prefix = digest.startsWith('sha256:') ? 'sha256:' : ''
  const body = prefix ? digest.slice(prefix.length) : digest
  return prefix + (body.length > 10 ? body.slice(0, 10) : body)
}

function apiMessage(e: unknown, fallback: string): string {
  if (!(e instanceof ApiError)) return fallback
  const detail = e.detail
  if (detail && typeof detail === 'object' && !Array.isArray(detail)) {
    const parts = Object.entries(detail as Record<string, unknown>)
      .filter(([, v]) => v !== null && v !== undefined && v !== '')
      .map(([k, v]) => `${k}: ${String(v)}`)
    if (parts.length && !e.message.includes(parts[0])) {
      return `${e.message}（${parts.join('；')}）`
    }
  }
  return e.message
}

async function load() {
  targetDigest.value = ''
  acknowledged.value = false
  reason.value = ''
  result.value = null
  data.value = null
  loadError.value = null
  unavailable.value = false
  if (!props.unitId) {
    unavailable.value = true
    loading.value = false
    return
  }
  loading.value = true
  try {
    data.value = await getRollbackVersions(props.unitId)
  } catch (e) {
    if (e instanceof ApiError && e.code === 40001) {
      unavailable.value = true
    } else {
      loadError.value = apiMessage(e, '读取版本清单失败')
    }
  } finally {
    loading.value = false
  }
}

async function submit() {
  const chosen = target.value
  if (!chosen || !props.unitId) return
  const digest = shortDigest(chosen.digest)
  try {
    await ElMessageBox.confirm(
      `将把该单元的生产镜像回滚到 ${chosen.publishNo}（${digest}）。` +
        '回滚立即执行、不走审批，且只切换镜像，代码不会随之后退。',
      '确认回滚到历史版本',
      { confirmButtonText: '确认回滚', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    const res = await executeRollback({
      unitId: props.unitId,
      targetDigest: chosen.digest,
      reason: reason.value.trim()
    })
    ElMessage.success(`已提交回滚 #${res.rollbackId} · 流水线 ${res.pipelineNo}`)
    if (res.freezeWarning) ElMessage.warning(res.freezeWarning)
    emit('changed')
    // The version list is re-read so the drawer reflects the new current image once rolled back.
    await load()
    result.value = res
  } catch (e) {
    ElMessage.error(apiMessage(e, '回滚失败'))
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) load()
  }
)
</script>

<template>
  <el-drawer
    v-model="visible"
    class="rollback-drawer"
    size="640px"
    :close-on-click-modal="false"
    :destroy-on-close="false"
  >
    <template #header>
      <div class="drawer-head">
        <span class="drawer-title">生产回滚</span>
        <span v-if="unitName" class="weak small">{{ unitName }}</span>
        <span class="weak small mono">{{ publishNo }}</span>
      </div>
    </template>

    <div v-if="loading" class="rollback-skeleton">
      <el-skeleton :rows="8" animated />
    </div>

    <EmptyState
      v-else-if="unavailable"
      title="该单元还没有成功的生产部署，暂不可回滚"
      :hint="
        unitId
          ? '先完成一次生产发布，成功部署过生产镜像后才能回滚。'
          : '该发布单没有关联部署单元，无法生成版本清单。'
      "
    />

    <template v-else-if="data">
      <div class="rollback">
        <el-alert
          v-if="loadError"
          type="error"
          :closable="false"
          show-icon
          :title="loadError"
          class="block-alert"
        />

        <el-alert
          v-if="result"
          type="success"
          :closable="false"
          show-icon
          :title="`已提交回滚 #${result.rollbackId} · 流水线 ${result.pipelineNo}`"
        >
          <template #default>
            <div class="result-line">
              目标镜像 <span class="mono">{{ shortDigest(result.targetDigest) }}</span>
            </div>
            <div v-if="result.freezeWarning" class="result-warn">{{ result.freezeWarning }}</div>
          </template>
        </el-alert>

        <section class="version-block">
          <div class="block-head">
            <span class="block-title">当前线上版本</span>
            <span class="weak small">生产正在运行的镜像</span>
          </div>
          <div class="version-card version-current">
            <div class="version-top">
              <span class="mono strong digest" :title="data.current.digest">
                {{ shortDigest(data.current.digest) }}
              </span>
              <span class="chip chip-accent">线上</span>
              <span class="topbar-spacer" />
              <span class="envs">
                <span
                  v-for="env in data.current.environments"
                  :key="env.kind"
                  class="chip"
                >{{ env.label }}</span>
                <span v-if="!data.current.environments.length" class="weak small">—</span>
              </span>
            </div>
            <div class="version-facts">
              <span><span class="weak small">发布单</span> <span class="mono">{{ data.current.publishNo }}</span></span>
              <span><span class="weak small">完成时间</span> <span class="mono">{{ formatDateTime(data.current.finishedAt) }}</span></span>
              <span><span class="weak small">执行人</span> {{ data.current.executedBy || '—' }}</span>
              <span><span class="weak small">批次数</span> <span class="num">{{ data.current.batchCount }}</span></span>
            </div>
          </div>
        </section>

        <section class="version-block">
          <div class="block-head">
            <span class="block-title">历史版本</span>
            <span class="weak small">最多 10 个完整部署成功的目标，单选</span>
          </div>
          <div v-if="data.history.length" class="history-list">
            <label
              v-for="v in data.history"
              :key="v.digest"
              class="history-row"
              :class="{ selected: targetDigest === v.digest }"
            >
              <input
                v-model="targetDigest"
                class="history-radio"
                type="radio"
                name="rollback-target"
                :value="v.digest"
              />
              <span class="history-body">
                <span class="history-top">
                  <span class="mono strong" :title="v.digest">{{ shortDigest(v.digest) }}</span>
                  <span class="weak small">发布单 {{ v.publishNo }}</span>
                  <span class="topbar-spacer" />
                  <span class="envs">
                    <span v-for="env in v.environments" :key="env.kind" class="chip">{{ env.label }}</span>
                  </span>
                </span>
                <span class="history-facts weak small">
                  完成 {{ formatDateTime(v.finishedAt) }} · 执行人 {{ v.executedBy || '—' }} ·
                  批次 {{ v.batchCount }}
                </span>
              </span>
            </label>
          </div>
          <div v-else class="history-empty weak small">
            没有可回滚的历史版本：当前镜像已是唯一成功的生产版本。
          </div>
        </section>

        <section class="warn-box">
          <div class="warn-title">必读提示</div>
          <ul class="warn-list">
            <li v-for="(w, i) in data.warnings" :key="i">{{ w }}</li>
          </ul>
          <el-checkbox v-model="acknowledged" class="ack">
            我已了解：仅回滚镜像，代码需人工回退
          </el-checkbox>
        </section>

        <section class="reason-block">
          <div class="block-head">
            <span class="block-title">回滚原因</span>
            <span class="weak small">必填，1–200 字，作为操作留痕</span>
          </div>
          <el-input
            v-model="reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="说明为什么回滚，例如：新版本接口 5xx 上升"
          />
        </section>
      </div>
    </template>

    <template #footer>
      <div class="drawer-foot">
        <span v-if="confirmBlock" class="weak small foot-hint">{{ confirmBlock }}</span>
        <span class="topbar-spacer" />
        <el-button @click="visible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!canConfirm"
          :loading="submitting"
          :title="confirmBlock || '确认回滚'"
          @click="submit"
        >
          确认回滚
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.rollback-skeleton {
  padding: 4px 2px;
}
.drawer-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.drawer-title {
  font-size: 16px;
  font-weight: 600;
}
.rollback {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.block-alert {
  margin: 0;
}
.version-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.block-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
}
.version-card {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  padding: 12px 14px;
}
.version-current {
  border-left: 3px solid var(--accent);
  background: var(--accent-weak);
}
.version-top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.digest {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.version-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 20px;
  margin-top: 8px;
  font-size: 13px;
}
.envs {
  display: inline-flex;
  gap: 6px;
  flex-wrap: wrap;
}
.chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--surface-3);
  color: var(--text-2);
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
}
.chip-accent {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  background: var(--surface-2);
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.history-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  cursor: pointer;
  transition: border-color 140ms ease, background-color 140ms ease;
}
.history-row:hover {
  border-color: var(--border-strong);
}
.history-row.selected {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--border));
  background: var(--accent-weak);
}
.history-radio {
  margin-top: 3px;
  accent-color: var(--accent);
  flex: none;
}
.history-body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}
.history-top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.history-facts {
  display: block;
}
.history-empty {
  padding: 14px 12px;
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
}
.warn-box {
  border: 1px solid color-mix(in srgb, var(--warn) 35%, var(--border));
  border-radius: var(--radius);
  background: var(--warn-weak);
  padding: 12px 14px;
}
.warn-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--warn);
}
.warn-list {
  margin: 6px 0 10px;
  padding-left: 18px;
  font-size: 12px;
  color: var(--text-2);
}
.warn-list li {
  margin: 2px 0;
}
.ack {
  --el-checkbox-font-size: 12px;
}
.reason-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.result-line {
  font-size: 12px;
  color: var(--text-2);
  margin-top: 2px;
}
.result-warn {
  margin-top: 4px;
  font-size: 12px;
  color: var(--warn);
}
.drawer-foot {
  display: flex;
  align-items: center;
  gap: 10px;
}
.foot-hint {
  margin-right: 4px;
}
</style>