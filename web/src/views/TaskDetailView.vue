<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addTaskMembers,
  createItem,
  getDaily,
  getMe,
  getTask,
  listCodingTasks,
  listDaily,
  listTaskItems,
  listUnits,
  publishItem,
  startDaily
} from '../api'
import type {
  DailyDetailView,
  DailyRecordView,
  ItemView,
  MeView,
  TaskDetailView,
  UnitView
} from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import StatusDot from '../components/StatusDot.vue'
import KeyValue from '../components/KeyValue.vue'
import { formatDateTime, shortSha, statusLabel } from '../utils/format'

const route = useRoute()
const router = useRouter()
const taskId = Number(route.params.id)

const detail = ref<TaskDetailView | null>(null)
const items = ref<ItemView[]>([])
const units = ref<UnitView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const publishing = ref<number | null>(null)

// --------------------------------------------------------------- daily (P5-A)
const me = ref<MeView | null>(null)
/** `release:daily` gates the row action; without it the button stays disabled with the reason. */
const canDaily = computed(() => me.value?.perms.includes('release:daily') ?? false)
const dailyItems = computed(() => items.value.filter((i) => i.type === 'UNIT'))
const dailyItemId = ref<number | null>(null)
const selectedDailyItem = computed(
  () => dailyItems.value.find((i) => i.id === dailyItemId.value) ?? null
)
const dailyRecords = ref<DailyRecordView[]>([])
const dailyTotal = ref(0)
const dailyPageNo = ref(1)
const dailyPageSize = ref(10)
const dailyLoading = ref(false)
const dailyError = ref<string | null>(null)
const startingDaily = ref<number | null>(null)

const dailyDrawer = ref(false)
const dailyDetail = ref<DailyDetailView | null>(null)
const dailyDetailLoading = ref(false)
const dailyDetailError = ref<string | null>(null)

/** Task kind labels; the server sends the machine value (design §6 keeps enums server-side). */
const TASK_LABELS: Record<string, string> = {
  CODE_CHECK: '代码检查',
  PKG_BUILD: '打包构建',
  IMG_BUILD: '镜像构建',
  DEPLOY: '部署',
  DAILY_DEPLOY: '日常部署'
}
const taskLabel = (type: string) => TASK_LABELS[type] ?? type

const itemDialog = ref(false)
const itemForm = reactive({ unitId: undefined as number | undefined, version: '', planReleaseDate: '' })
// The branch is not an input (C21): it comes from the unit's BUILD binding, so the dialog
// shows what the server will use and blocks the submit when nothing is configured.
const unitBranch = ref<string | null>(null)
const branchLoading = ref(false)
const branchReady = computed(() => Boolean(unitBranch.value))
const memberDialog = ref(false)
const memberForm = reactive({ userId: '', role: 'TEST' })

const task = computed(() => detail.value?.task)
const ratePercent = computed(() => Math.round((task.value?.rate ?? 0) * 100))

async function load() {
  loading.value = true
  error.value = null
  try {
    const [d, i, m] = await Promise.all([
      getTask(taskId),
      listTaskItems(taskId),
      getMe().catch(() => null)
    ])
    detail.value = d
    items.value = i
    me.value = m
    if (d.task.subsystemId) {
      const page = await listUnits({ pageNo: 1, pageSize: 100, subsystemId: d.task.subsystemId })
      units.value = page.records
    }
    if (dailyItemId.value === null || !dailyItems.value.some((it) => it.id === dailyItemId.value)) {
      dailyItemId.value = dailyItems.value[0]?.id ?? null
    }
    await loadDaily()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ------------------------------------------------------------------ daily load

async function loadDaily() {
  if (!dailyItemId.value) {
    dailyRecords.value = []
    dailyTotal.value = 0
    return
  }
  dailyLoading.value = true
  dailyError.value = null
  try {
    const page = await listDaily(dailyItemId.value, dailyPageNo.value, dailyPageSize.value)
    dailyRecords.value = page.records
    dailyTotal.value = page.total
  } catch (e) {
    dailyError.value = e instanceof ApiError ? e.message : '加载日常发布记录失败'
    dailyRecords.value = []
    dailyTotal.value = 0
  } finally {
    dailyLoading.value = false
  }
}

function onDailyItemChange() {
  dailyPageNo.value = 1
  loadDaily()
}

function onDailyPageChange() {
  loadDaily()
}

function onDailySizeChange() {
  dailyPageNo.value = 1
  loadDaily()
}

function onDailyRowClick(row: DailyRecordView) {
  openDaily(row.pipelineNo)
}

/** Opens the records block on the row's item (the table row action next to 「日常」). */
function openDailyRecords(row: ItemView) {
  dailyItemId.value = row.id
  dailyPageNo.value = 1
  loadDaily()
  document.querySelector('.daily-block')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/**
 * Starts one daily release after the confirmation that spells out its boundary (design §6): it
 * takes no unit lock, changes neither the item status nor a 发布单, and may run in parallel. The
 * server owns the target environment (daily DE, falling back to the test DE).
 */
async function startDailyFor(row: ItemView) {
  if (!canDaily.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认对「${row.unitName ?? row.unitId} / ${row.version}」发起日常发布？` +
        '日常发布不占锁（不占用部署单元锁）、不改变更项状态、不生成发布单，可与在线发布并行；' +
        '目标环境优先使用该单元的日常 DE，没有日常 DE 时回退到测试 DE。',
      '日常发布确认',
      { confirmButtonText: '发起日常发布', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  startingDaily.value = row.id
  try {
    const started = await startDaily(row.id)
    ElMessage.success(`已发起日常发布 ${started.pipelineNo}`)
    dailyItemId.value = row.id
    dailyPageNo.value = 1
  } catch (e) {
    // 40001 (no DE/binding, archived task) and 40009 (no permission/scope) carry the operator text.
    ElMessage.error(e instanceof ApiError ? e.message : '日常发布发起失败')
    return
  } finally {
    startingDaily.value = null
  }
  await loadDaily()
}

async function openDaily(pipelineNo: string) {
  dailyDrawer.value = true
  dailyDetail.value = null
  dailyDetailError.value = null
  dailyDetailLoading.value = true
  try {
    dailyDetail.value = await getDaily(pipelineNo)
  } catch (e) {
    dailyDetailError.value = e instanceof ApiError ? e.message : '加载日常发布详情失败'
  } finally {
    dailyDetailLoading.value = false
  }
}

function openItemDialog() {
  itemForm.unitId = units.value[0]?.id
  itemForm.version = ''
  itemForm.planReleaseDate = ''
  itemDialog.value = true
  loadUnitBranch()
}

/** Reads the unit's read-only branch so the dialog can say what will be built. */
async function loadUnitBranch() {
  const unitId = itemForm.unitId
  unitBranch.value = null
  if (!unitId) {
    return
  }
  branchLoading.value = true
  try {
    const bindings = await listCodingTasks(unitId)
    const build = bindings.find((b) => b.taskKind === 'BUILD' && b.enabled !== false)
    if (!build?.paramsJson) {
      unitBranch.value = null
      return
    }
    const params = JSON.parse(build.paramsJson) as { branch?: unknown }
    unitBranch.value = typeof params.branch === 'string' ? params.branch : null
  } catch {
    unitBranch.value = null
  } finally {
    if (itemForm.unitId === unitId) {
      branchLoading.value = false
    }
  }
}

function goToUnit() {
  if (itemForm.unitId) {
    router.push(`/units/${itemForm.unitId}`)
  }
}

async function submitItem() {
  if (!itemForm.unitId || !itemForm.version.trim() || !itemForm.planReleaseDate) {
    ElMessage.warning('部署单元、变更原因与计划发布时间必填')
    return
  }
  if (!branchReady.value) {
    ElMessage.warning('该单元没有只读分支，请先在单元详情页配置 BUILD 的只读分支')
    return
  }
  try {
    await createItem({
      taskId,
      unitId: itemForm.unitId,
      version: itemForm.version.trim(),
      planReleaseDate: itemForm.planReleaseDate
    })
    itemDialog.value = false
    ElMessage.success('变更项已创建（分支由 CODING 构建任务带出）')
    await load()
  } catch (e) {
    if (e instanceof ApiError) {
      const missing = (e.detail as { missing?: string[] } | undefined)?.missing
      if (missing?.includes('BUILD_BRANCH')) {
        ElMessage.error('该单元的构建任务没有只读分支（40011），先在单元详情页填写后再创建')
        unitBranch.value = null
        return
      }
      ElMessage.error(e.message)
    } else {
      ElMessage.error('创建失败')
    }
  }
}

async function publish(row: ItemView) {
  try {
    await ElMessageBox.confirm(
      `确认发布「${row.unitName ?? row.unitId} / ${row.version}」？发布后会自动构建并部署测试环境。`,
      '发布确认',
      { confirmButtonText: '发布', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  publishing.value = row.id
  try {
    const result = await publishItem(row.id)
    ElMessage.success(`已创建发布单 ${result.publishNo}`)
    router.push(`/releases/${result.publishNo}`)
  } catch (e) {
    if (e instanceof ApiError) {
      const missing = (e.detail as { missing?: string[] } | undefined)?.missing
      ElMessage.error(missing?.length ? `${e.message}（缺少 ${missing.join('、')}）` : e.message)
    } else {
      ElMessage.error('发布失败')
    }
  } finally {
    publishing.value = null
  }
}

async function submitMember() {
  if (!memberForm.userId.trim()) {
    ElMessage.warning('成员工号必填')
    return
  }
  try {
    await addTaskMembers(taskId, [{ userId: memberForm.userId.trim(), role: memberForm.role }])
    memberDialog.value = false
    memberForm.userId = ''
    ElMessage.success('成员已添加')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '添加失败')
  }
}
</script>

<template>
  <div>
    <PageHeader :title="task?.name ?? '任务详情'" :description="task ? `${task.subsystemName ?? ''} · 创建人 ${task.creator}` : ''">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" :disabled="!units.length" @click="openItemDialog">新建变更项</el-button>
    </PageHeader>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <div class="grid" style="grid-template-columns: minmax(0, 1fr) 320px">
      <div class="stack">
        <DataSection title="进度">
          <div class="row" style="gap: 14px">
            <el-progress :percentage="ratePercent" :stroke-width="6" style="flex: 1" :show-text="false" />
            <span class="mono">{{ task?.done ?? 0 }}/{{ task?.total ?? 0 }}</span>
            <el-tag v-if="task?.hasBlocking" size="small" type="warning" effect="plain">有阻塞</el-tag>
          </div>
        </DataSection>

        <DataSection class="items-block" title="变更项" :subtitle="`${items.length} 项`" flush>
          <el-table v-if="items.length" :data="items" row-key="id" style="width: 100%">
            <el-table-column label="CRID / 分支" width="170">
              <template #default="{ row }">
                <span class="mono">{{ row.crid }}</span>
                <div class="mono weak small truncate" :title="row.sourceBranch">{{ row.sourceBranch }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="version" label="变更原因" width="120">
              <template #default="{ row }">
                <span class="truncate" :title="row.version">{{ row.version }}</span>
              </template>
            </el-table-column>
            <el-table-column label="部署单元" width="160">
              <template #default="{ row }">
                <router-link :to="`/units/${row.unitId}`" class="truncate">{{ row.unitName ?? row.unitId }}</router-link>
                <div class="weak small">{{ row.type }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="planReleaseDate" label="计划发布" width="104">
              <template #default="{ row }"><span class="mono weak">{{ row.planReleaseDate }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <StatusDot :status="row.status" pulse />
              </template>
            </el-table-column>
            <el-table-column label="门禁" width="96">
              <template #default="{ row }">
                <StatusDot :status="row.gateStatus" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="176" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button
                    link
                    type="primary"
                    :loading="publishing === row.id"
                    @click="publish(row)"
                  >
                    发布
                  </el-button>
                  <template v-if="row.type !== 'LIB'">
                    <el-button
                      link
                      type="primary"
                      class="daily-start"
                      :loading="startingDaily === row.id"
                      :disabled="!canDaily"
                      :title="canDaily ? '发起日常发布（不占锁、不改变更项状态）' : '当前账号缺少 release:daily 权限，无法发起日常发布'"
                      @click="startDailyFor(row)"
                    >
                      日常
                    </el-button>
                    <el-button link class="daily-open" @click="openDailyRecords(row)">
                      记录
                    </el-button>
                  </template>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState v-else-if="!loading" title="还没有变更项" hint="一个任务可以关联多个部署单元，发布按单元独立进行。" />
          <div v-else style="padding: 24px"><el-skeleton :rows="4" animated /></div>
        </DataSection>

        <DataSection
          class="daily-block"
          title="日常发布"
          :subtitle="selectedDailyItem ? `${selectedDailyItem.crid} · 共 ${dailyTotal} 条` : '暂无变更项'"
          flush
        >
          <template #actions>
            <el-select
              v-if="dailyItems.length > 1"
              v-model="dailyItemId"
              class="daily-item-select"
              size="small"
              placeholder="选择变更项"
              @change="onDailyItemChange"
            >
              <el-option
                v-for="it in dailyItems"
                :key="it.id"
                :label="`${it.crid} · ${it.version}`"
                :value="it.id"
              />
            </el-select>
            <el-button text :loading="dailyLoading" @click="loadDaily">刷新</el-button>
          </template>

          <el-alert
            v-if="dailyError"
            type="error"
            :closable="false"
            show-icon
            :title="dailyError"
            style="margin: 12px 18px"
          />

          <el-table
            v-if="dailyRecords.length"
            :data="dailyRecords"
            row-key="pipelineNo"
            class="daily-table"
            style="width: 100%"
            @row-click="onDailyRowClick"
          >
            <el-table-column label="流水线" min-width="178">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  class="mono daily-no"
                  @click.stop="openDaily(row.pipelineNo)"
                >
                  {{ row.pipelineNo }}
                </el-button>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                <span class="mono weak">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="执行人" width="96">
              <template #default="{ row }">{{ row.requestedBy || '—' }}</template>
            </el-table-column>
            <el-table-column label="分支" min-width="140">
              <template #default="{ row }">
                <span class="mono truncate" :title="row.branch">{{ row.branch || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="commit" width="112">
              <template #default="{ row }">
                <span class="mono weak" :title="row.commitSha || ''">{{ shortSha(row.commitSha) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="100">
              <template #default="{ row }">
                <StatusDot :status="row.status" domain="node" />
              </template>
            </el-table-column>
            <el-table-column label="日志" width="88">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  title="打开日常详情查看引擎日志"
                  @click.stop="openDaily(row.pipelineNo)"
                >
                  查看
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState
            v-else-if="!dailyLoading"
            title="还没有日常发布记录"
            hint="点击变更项行的「日常」发起；日常不占部署锁、不改变更项状态，可并行多次。"
          />
          <div v-else style="padding: 24px"><el-skeleton :rows="3" animated /></div>

          <div v-if="dailyTotal > 0" class="daily-pager">
            <el-pagination
              v-model:current-page="dailyPageNo"
              v-model:page-size="dailyPageSize"
              :total="dailyTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              background
              @current-change="onDailyPageChange"
              @size-change="onDailySizeChange"
            />
          </div>
        </DataSection>
      </div>

      <div class="stack">
        <DataSection title="任务信息">
          <KeyValue label="任务 ID" :value="task?.id" mono />
          <KeyValue label="系统" :value="task?.subsystemName" />
          <KeyValue label="创建人" :value="task?.creator" />
          <KeyValue label="预计完成" :value="task?.planFinishDate" />
          <KeyValue label="目的" :value="task?.purpose" />
          <KeyValue label="创建时间" :value="formatDateTime(task?.createdAt)" mono />
        </DataSection>

        <DataSection title="成员" :subtitle="`${detail?.members.length ?? 0} 人`">
          <template #actions>
            <el-button text @click="memberDialog = true">添加</el-button>
          </template>
          <div v-if="detail?.members.length" class="stack" style="gap: 8px">
            <div v-for="m in detail.members" :key="m.userId" class="member">
              <div class="avatar">{{ m.userId.slice(0, 1).toUpperCase() }}</div>
              <div class="member-text">
                <div class="mono">{{ m.userId }}</div>
                <div class="weak small">{{ m.name ?? '—' }}</div>
              </div>
              <el-tag size="small" effect="plain">{{ m.role }}</el-tag>
            </div>
          </div>
          <EmptyState v-else title="暂无成员" hint="测试人员与部署人员用于通知寻址。" />
        </DataSection>
      </div>
    </div>

    <el-dialog v-model="itemDialog" title="新建变更项" width="520px">
      <el-form label-position="top">
        <el-form-item label="部署单元" required>
          <el-select
            v-model="itemForm.unitId"
            placeholder="选择部署单元"
            style="width: 100%"
            @change="loadUnitBranch"
          >
            <el-option v-for="u in units" :key="u.id" :label="`${u.name} · ${u.type}`" :value="u.id" />
          </el-select>
          <div v-if="branchLoading" class="weak small" style="margin-top: 6px">读取只读分支…</div>
          <div v-else-if="unitBranch" class="branch-hint small">
            只读分支 <code class="mono">{{ unitBranch }}</code>（来自 BUILD 任务绑定，不可在此修改）
          </div>
          <div v-else class="branch-missing small">
            该单元的 BUILD 绑定没有只读分支，创建会被拒绝（40011）。
            <el-button text type="primary" size="small" @click="goToUnit">去配置</el-button>
          </div>
        </el-form-item>
        <div class="grid grid-2">
          <el-form-item label="变更原因" required>
            <el-input v-model="itemForm.version" placeholder="例如 V1.2.0" maxlength="64" />
          </el-form-item>
          <el-form-item label="计划发布时间" required>
            <el-date-picker v-model="itemForm.planReleaseDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!branchReady" @click="submitItem">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="memberDialog" title="添加成员" width="420px">
      <el-form label-position="top">
        <el-form-item label="工号" required>
          <el-input v-model="memberForm.userId" placeholder="例如 01388007" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="memberForm.role" style="width: 100%">
            <el-option v-for="r in ['DEV', 'TEST', 'OPS', 'PRODUCT', 'OWNER']" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialog = false">取消</el-button>
        <el-button type="primary" @click="submitMember">添加</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="dailyDrawer"
      class="daily-drawer"
      :title="dailyDetail ? `日常发布 ${dailyDetail.pipelineNo}` : '日常发布'"
      size="600px"
    >
      <div v-if="dailyDetailLoading" class="drawer-pad">
        <el-skeleton :rows="5" animated />
      </div>
      <el-alert
        v-else-if="dailyDetailError"
        type="error"
        :closable="false"
        show-icon
        :title="dailyDetailError"
      />
      <div v-else-if="dailyDetail" class="stack daily-detail">
        <div class="daily-head">
          <StatusDot :status="dailyDetail.status" domain="node" />
          <span class="weak small">不占部署锁 · 不生成发布单 · 不改变更项状态</span>
        </div>

        <div class="daily-meta">
          <KeyValue label="发起人" :value="dailyDetail.requestedBy" />
          <KeyValue label="分支" :value="dailyDetail.branch" mono />
          <KeyValue label="commit" :value="dailyDetail.commitSha" mono />
          <KeyValue label="镜像" :value="dailyDetail.imageDigest" mono />
          <KeyValue label="创建时间" :value="formatDateTime(dailyDetail.createdAt)" mono />
          <KeyValue label="开始时间" :value="formatDateTime(dailyDetail.startAt)" mono />
          <KeyValue label="结束时间" :value="formatDateTime(dailyDetail.endAt)" mono />
        </div>

        <div v-for="node in dailyDetail.nodes" :key="node.nodeType" class="daily-node">
          <div class="daily-node-head">
            <span class="strong">{{ statusLabel(node.nodeType) }}</span>
            <span class="mono weak small">{{ node.nodeType }}</span>
            <span class="daily-node-spacer" />
            <StatusDot :status="node.status" domain="node" />
          </div>
          <el-table :data="node.tasks" class="daily-tasks" style="width: 100%">
            <el-table-column label="任务" min-width="130">
              <template #default="{ row }">
                <div>{{ taskLabel(row.taskType) }}</div>
                <div class="mono weak small">{{ row.taskType }}</div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="92">
              <template #default="{ row }">
                <StatusDot :status="row.status" domain="task" />
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="170">
              <template #default="{ row }">
                <span class="small" :title="row.summary || ''">{{ row.summary || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="日志" width="88">
              <template #default="{ row }">
                <a
                  v-if="row.externalUrl"
                  :href="row.externalUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="log-link"
                >
                  打开日志
                </a>
                <span v-else class="weak">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <p class="weak small daily-note">
          日常发布复用单元唯一的构建任务，部署到日常 DE（没有时回退测试 DE）；日常镜像不进入上线候选，
          上线仍需走完整构建与测试部署。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.daily-item-select {
  width: 208px;
}
.daily-pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
}
.daily-table :deep(.el-table__row) {
  cursor: pointer;
}
.daily-block {
  scroll-margin-top: 72px;
}
.drawer-pad {
  padding: 8px 0;
}
.daily-detail {
  gap: 16px;
}
.daily-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.daily-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.daily-meta :deep(.kv) {
  grid-template-columns: 84px 1fr;
}
.daily-node {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.daily-node-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--surface-3);
  border-bottom: 1px solid var(--border);
}
.daily-node-spacer {
  flex: 1;
}
.daily-tasks :deep(.el-table__cell) {
  padding: 6px 0;
}
.daily-note {
  margin: 0;
  line-height: 1.6;
}
.log-link {
  font-size: 12px;
}
.member {
  display: flex;
  align-items: center;
  gap: 10px;
}
.member-text {
  flex: 1;
  min-width: 0;
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: var(--accent-weak);
  color: var(--accent);
  font-size: 11px;
  font-weight: 600;
}
.branch-hint {
  margin-top: 6px;
  color: var(--text-2);
}
.branch-hint code {
  color: var(--text);
}
.branch-missing {
  margin-top: 6px;
  color: var(--warn);
}
@media (max-width: 960px) {
  .grid {
    grid-template-columns: 1fr !important;
  }
  .daily-meta {
    grid-template-columns: 1fr;
  }
}
</style>