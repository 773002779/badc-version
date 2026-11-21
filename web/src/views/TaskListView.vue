<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createTask, listSubsystems, listTasks } from '../api'
import type { PageResult, SubsystemView, TaskView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import { formatDateTime } from '../utils/format'

const router = useRouter()

const rows = ref<PageResult<TaskView> | null>(null)
const subsystems = ref<SubsystemView[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const filters = reactive({ subsystemId: undefined as number | undefined, status: '', keyword: '' })
const pager = reactive({ pageNo: 1, pageSize: 10 })

const dialog = ref(false)
const saving = ref(false)
const form = reactive({ name: '', subsystemId: undefined as number | undefined, planFinishDate: '', purpose: '新功能' })

const STATUS_OPTIONS = ['ACTIVE', 'ARCHIVED']

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await listTasks({
      pageNo: pager.pageNo,
      pageSize: pager.pageSize,
      subsystemId: filters.subsystemId,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined
    })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadSubsystems() {
  try {
    const page = await listSubsystems(1, 100)
    subsystems.value = page.records
  } catch {
    subsystems.value = []
  }
}

onMounted(async () => {
  await Promise.all([load(), loadSubsystems()])
})

function search() {
  pager.pageNo = 1
  load()
}

function openCreate() {
  form.name = ''
  form.subsystemId = subsystems.value[0]?.id
  form.planFinishDate = ''
  form.purpose = '新功能'
  dialog.value = true
}

async function submitCreate() {
  if (!form.name.trim() || !form.subsystemId) {
    ElMessage.warning('任务名称与所属子系统必填')
    return
  }
  saving.value = true
  try {
    const id = await createTask({
      name: form.name.trim(),
      subsystemId: form.subsystemId,
      planFinishDate: form.planFinishDate || undefined,
      purpose: form.purpose || undefined
    })
    dialog.value = false
    ElMessage.success('任务已创建')
    router.push(`/tasks/${id}`)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '创建失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="任务" description="一次发布任务可关联多个变更项，发布按部署单元独立进行。">
      <el-button type="primary" @click="openCreate">新建任务</el-button>
    </PageHeader>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <DataSection title="发布任务" flush>
      <template #actions>
        <div class="row wrap">
          <el-select v-model="filters.subsystemId" clearable placeholder="全部子系统" style="width: 180px" @change="search">
            <el-option v-for="s in subsystems" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 140px" @change="search">
            <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="s" :value="s" />
          </el-select>
          <el-input
            v-model="filters.keyword"
            placeholder="任务名称"
            clearable
            style="width: 180px"
            @keyup.enter="search"
            @clear="search"
          />
          <el-button @click="search">查询</el-button>
        </div>
      </template>

      <el-table v-if="rows && rows.records.length" :data="rows.records" row-key="id" style="width: 100%">
        <el-table-column prop="name" label="任务" min-width="200">
          <template #default="{ row }">
            <router-link :to="`/tasks/${row.id}`" class="strong">{{ row.name }}</router-link>
            <div class="weak small">{{ row.subsystemName ?? '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="creator" label="创建人" width="110" />
        <el-table-column prop="planFinishDate" label="预计完成" width="120" />
        <el-table-column prop="purpose" label="目的" width="110" />
        <el-table-column label="完成度" width="220">
          <template #default="{ row }">
            <div class="row" style="gap: 10px">
              <el-progress :percentage="Math.round((row.rate ?? 0) * 100)" :show-text="false" :stroke-width="4" style="flex: 1" />
              <span class="mono weak">{{ row.done }}/{{ row.total }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="阻塞" width="90">
          <template #default="{ row }">
            <span class="small" :class="{ 'text-warn': row.hasBlocking }">{{ row.hasBlocking ? '有' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            <span class="mono weak">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState v-else-if="!loading" title="没有匹配的任务" hint="调整筛选条件，或新建一个任务。" />
      <div v-else style="padding: 24px"><el-skeleton :rows="5" animated /></div>

      <div v-if="rows && rows.total > pager.pageSize" class="pager">
        <el-pagination
          v-model:current-page="pager.pageNo"
          :page-size="pager.pageSize"
          :total="rows.total"
          layout="prev, pager, next, total"
          background
          @current-change="load"
        />
      </div>
    </DataSection>

    <el-dialog v-model="dialog" title="新建发布任务" width="520px">
      <el-form label-position="top">
        <el-form-item label="任务名称" required>
          <el-input v-model="form.name" placeholder="例如 202611-V1.2.0" maxlength="128" />
        </el-form-item>
        <el-form-item label="所属子系统" required>
          <el-select v-model="form.subsystemId" placeholder="选择子系统" style="width: 100%">
            <el-option v-for="s in subsystems" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <div class="grid grid-2">
          <el-form-item label="预计完成">
            <el-date-picker v-model="form.planFinishDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="任务目的">
            <el-select v-model="form.purpose" style="width: 100%">
              <el-option label="新功能" value="新功能" />
              <el-option label="缺陷修复" value="缺陷修复" />
              <el-option label="优化" value="优化" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
}
.text-warn {
  color: var(--warn);
}
</style>