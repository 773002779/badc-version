<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead
} from '../api'
import type { NotificationView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import { formatDateTime } from '../utils/format'
import { bumpNotifications, notificationTarget } from '../utils/notification'

const router = useRouter()

const rows = ref<NotificationView[]>([])
const total = ref(0)
const unread = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref<string | null>(null)
/** `unreadOnly` is a server-side filter (design §6), not a client-side hide of the current page. */
const filter = ref<'all' | 'unread'>('all')
const marking = ref<number | null>(null)
const markingAll = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    const page = await listNotifications({
      page: pageNo.value,
      size: pageSize.value,
      unreadOnly: filter.value === 'unread'
    })
    rows.value = page.records
    total.value = page.total
    unread.value = page.unread
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载通知失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  pageNo.value = 1
  load()
}

function onSizeChange() {
  pageNo.value = 1
  load()
}

function rowClassName({ row }: { row: NotificationView }) {
  return row.read ? '' : 'row-unread'
}

/** Marks one row read locally and on the server; under the 未读 filter it then leaves the page. */
async function markOne(row: NotificationView) {
  if (row.read) return
  marking.value = row.id
  try {
    await markNotificationRead(row.id)
    row.read = true
    unread.value = Math.max(0, unread.value - 1)
    bumpNotifications()
    if (filter.value === 'unread') {
      await load()
    }
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '标记已读失败')
  } finally {
    marking.value = null
  }
}

async function markAll() {
  try {
    await ElMessageBox.confirm(
      `将把 ${unread.value} 条未读通知标记为已读。`,
      '全部已读',
      { type: 'warning', confirmButtonText: '全部已读', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  markingAll.value = true
  try {
    const result = await markAllNotificationsRead()
    ElMessage.success(`已标记 ${result.updated} 条为已读`)
    bumpNotifications()
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '标记全部已读失败')
  } finally {
    markingAll.value = false
  }
}

/** Opening a row marks it read first, then follows the shared object-type navigation rule. */
async function openRow(row: NotificationView) {
  const target = notificationTarget(row)
  if (!target) return
  if (!row.read) {
    try {
      await markNotificationRead(row.id)
      row.read = true
      unread.value = Math.max(0, unread.value - 1)
      bumpNotifications()
    } catch {
      // Navigation wins over bookkeeping: the row stays unread and can be retried.
    }
  }
  await router.push(target)
}

onMounted(() => load())
</script>

<template>
  <div>
    <PageHeader
      title="消息通知"
      description="构建、部署、审批、封板与锁超时事件的站内通知；企微提醒在设置页配置。"
    >
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button
        type="primary"
        :disabled="unread === 0 || markingAll"
        :loading="markingAll"
        @click="markAll"
      >
        全部已读
      </el-button>
    </PageHeader>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
      style="margin-bottom: 16px"
    />

    <DataSection
      title="通知"
      :subtitle="`${total} 条${unread > 0 ? ` · ${unread} 条未读` : ''}`"
      flush
    >
      <div class="filter-bar">
        <el-radio-group v-model="filter" @change="onFilterChange">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button label="unread">未读</el-radio-button>
        </el-radio-group>
        <span class="weak small">
          {{ filter === 'unread' ? '仅显示未读通知；标记已读后会从列表中移除。' : '按时间倒序，最新在前。' }}
        </span>
      </div>

      <el-table
        v-if="rows.length"
        :data="rows"
        row-key="id"
        :row-class-name="rowClassName"
        class="notify-table"
        style="width: 100%"
        @row-click="(row: NotificationView) => openRow(row)"
      >
        <el-table-column label="状态" width="76">
          <template #default="{ row }">
            <span class="state">
              <span class="state-dot" :class="{ on: !row.read }" />
              {{ row.read ? '已读' : '未读' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="300">
          <template #default="{ row }">
            <div class="notify-main">
              <span class="notify-title" :class="{ strong: !row.read }">{{ row.title }}</span>
              <span class="notify-body">{{ row.body }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="事件" width="130">
          <template #default="{ row }">
            <span class="event-chip">{{ row.eventLabel }}</span>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="160">
          <template #default="{ row }">
            <span class="mono weak">{{ row.objectLabel || row.objectId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="mono weak">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="148">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                v-if="!row.read"
                link
                type="primary"
                size="small"
                :loading="marking === row.id"
                @click.stop="markOne(row)"
              >
                标记已读
              </el-button>
              <el-button
                link
                size="small"
                :disabled="!notificationTarget(row)"
                @click.stop="openRow(row)"
              >
                查看
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-else-if="!loading"
        :title="filter === 'unread' ? '没有未读通知' : '还没有通知'"
        hint="构建、部署、审批、封板与锁超时事件会出现在这里。"
      />
      <div v-else class="loading-block"><el-skeleton :rows="5" animated /></div>

      <div v-if="total > 0" class="pager">
        <el-pagination
          v-model:current-page="pageNo"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </DataSection>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
}

.notify-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.notify-title {
  font-size: 13px;
  line-height: 1.45;
}

.notify-body {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
}

.state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
  font-size: 12px;
}

.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--border-strong);
}

.state-dot.on {
  background: var(--accent);
}

.event-chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface-3);
  color: var(--text-2);
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

/* Unread rows: the same accent tint the sidebar uses for the active item, nothing new. */
:deep(.el-table__row.row-unread > td.el-table__cell) {
  background: color-mix(in srgb, var(--accent-weak) 60%, var(--surface-2));
}

:deep(.el-table__row.row-unread:hover > td.el-table__cell) {
  background: var(--accent-weak);
}

.loading-block {
  padding: 24px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
}
</style>