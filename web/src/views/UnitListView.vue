<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listSubsystems, listUnits } from '../api'
import type { PageResult, SubsystemView, UnitView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'

const rows = ref<PageResult<UnitView> | null>(null)
const subsystems = ref<SubsystemView[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const filters = reactive({ subsystemId: undefined as number | undefined, type: '', keyword: '' })

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await listUnits({
      pageNo: 1,
      pageSize: 100,
      subsystemId: filters.subsystemId,
      type: filters.type || undefined,
      keyword: filters.keyword || undefined
    })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  load()
  try {
    subsystems.value = (await listSubsystems(1, 100)).records
  } catch {
    subsystems.value = []
  }
})

const subsystemName = (id: number) => subsystems.value.find((s) => s.id === id)?.name ?? `#${id}`
</script>

<template>
  <div>
    <PageHeader title="部署单元" description="构建与部署的最小单位；二方库无部署，仅产出制品。">
      <el-button @click="load">刷新</el-button>
    </PageHeader>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px" />

    <DataSection title="全部单元" :subtitle="`${rows?.total ?? 0} 个`" flush>
      <template #actions>
        <el-select v-model="filters.subsystemId" clearable placeholder="全部子系统" style="width: 190px" @change="load">
          <el-option v-for="s in subsystems" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <el-select v-model="filters.type" clearable placeholder="全部类型" style="width: 120px" @change="load">
          <el-option label="部署单元" value="UNIT" />
          <el-option label="二方库" value="LIB" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="名称" clearable style="width: 160px" @keyup.enter="load" @clear="load" />
      </template>

      <el-table v-if="rows && rows.records.length" :data="rows.records" row-key="id" style="width: 100%">
        <el-table-column prop="name" label="名称" min-width="170">
          <template #default="{ row }">
            <router-link :to="`/units/${row.id}`" class="strong">{{ row.name }}</router-link>
            <div class="weak small">{{ row.modulePath || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="子系统" min-width="160">
          <template #default="{ row }">{{ subsystemName(row.subsystemId) }}</template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="ownerId" label="owner" width="110" />
        <el-table-column prop="frameworkTemplate" label="框架" width="120" />
        <el-table-column prop="branchMode" label="分支模式" width="110" />
        <el-table-column prop="status" label="状态" width="90" />
      </el-table>
      <EmptyState v-else-if="!loading" title="还没有部署单元" hint="从某个子系统详情页新建。" />
      <div v-else style="padding: 24px"><el-skeleton :rows="4" animated /></div>
    </DataSection>
  </div>
</template>