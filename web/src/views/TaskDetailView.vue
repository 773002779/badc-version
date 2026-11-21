<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addTaskMembers,
  createItem,
  getTask,
  listCodingTasks,
  listTaskItems,
  listUnits,
  publishItem
} from '../api'
import type { ItemView, TaskDetailView, UnitView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import StatusDot from '../components/StatusDot.vue'
import KeyValue from '../components/KeyValue.vue'
import { formatDateTime } from '../utils/format'

const route = useRoute()
const router = useRouter()
const taskId = Number(route.params.id)

const detail = ref<TaskDetailView | null>(null)
const items = ref<ItemView[]>([])
const units = ref<UnitView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const publishing = ref<number | null>(null)

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
    const [d, i] = await Promise.all([getTask(taskId), listTaskItems(taskId)])
    detail.value = d
    items.value = i
    if (d.task.subsystemId) {
      const page = await listUnits({ pageNo: 1, pageSize: 100, subsystemId: d.task.subsystemId })
      units.value = page.records
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

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

        <DataSection title="变更项" :subtitle="`${items.length} 项`" flush>
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
            <el-table-column label="操作" width="76" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :loading="publishing === row.id"
                  @click="publish(row)"
                >
                  发布
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState v-else-if="!loading" title="还没有变更项" hint="一个任务可以关联多个部署单元，发布按单元独立进行。" />
          <div v-else style="padding: 24px"><el-skeleton :rows="4" animated /></div>
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
  </div>
</template>

<style scoped>
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
}
</style>