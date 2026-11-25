<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import { listTodos } from '../api'
import type { TodoBoardView, TodoGroupView, TodoItemView } from '../api/types'
import { ApiError } from '../api/http'
import PageHeader from '../components/PageHeader.vue'
import DataSection from '../components/DataSection.vue'
import EmptyState from '../components/EmptyState.vue'
import { formatDateTime, statusTone } from '../utils/format'

/**
 * The 待办中心 (P5-C design §3): the four server-computed queues rendered as `DataSection`s.
 *
 * The server decides membership, the first unsigned approval step and the unit scope; this page
 * renders `label` / `statusLabel` / `waitingHours` / `link` verbatim and never rebuilds a route.
 * The list asks for the endpoint's maximum `limit` so the centre shows a real queue instead of a
 * five-row teaser; `count` still comes back full, and a footer says when the list was capped.
 */
const PAGE_LIMIT = 20

type GroupKey = keyof TodoBoardView

const SECTIONS: Array<{ key: GroupKey; title: string; emptyTitle: string }> = [
  { key: 'test', title: '我的待测试', emptyTitle: '暂无待测试' },
  { key: 'approval', title: '我的审批', emptyTitle: '暂无待审批' },
  { key: 'execute', title: '待我执行', emptyTitle: '暂无待执行' },
  { key: 'mine', title: '我发起的', emptyTitle: '暂无我发起的任务' }
]

const EMPTY_GROUP: TodoGroupView = { count: 0, items: [] }

const board = ref<TodoBoardView | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const groups = computed<Record<GroupKey, TodoGroupView>>(() => ({
  test: board.value?.test ?? EMPTY_GROUP,
  approval: board.value?.approval ?? EMPTY_GROUP,
  execute: board.value?.execute ?? EMPTY_GROUP,
  mine: board.value?.mine ?? EMPTY_GROUP
}))

const total = computed(
  () =>
    groups.value.test.count +
    groups.value.approval.count +
    groups.value.execute.count +
    groups.value.mine.count
)

async function load() {
  loading.value = true
  error.value = null
  try {
    board.value = await listTodos(PAGE_LIMIT)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载待办失败'
  } finally {
    loading.value = false
  }
}

/** The object type words of the todo design; unknown values degrade to the raw word. */
const TYPE_LABELS: Record<string, string> = {
  ITEM: '变更项',
  ORDER: '发布单',
  TASK: '任务'
}

function typeLabel(objectType: string): string {
  return TYPE_LABELS[objectType] ?? objectType
}

/** `waitingHours` is a server string of whole hours; zero is the "just arrived" case. */
function waitingText(waitingHours?: string | null): string {
  const hours = Number(waitingHours ?? 0)
  if (!Number.isFinite(hours) || hours <= 0) return '刚刚'
  return `已等待 ${Math.floor(hours)} 小时`
}

function waitingTitle(row: TodoItemView): string {
  return row.waitingSince ? `自 ${formatDateTime(row.waitingSince)} 起等待` : ''
}

/** The badge of one section: `mine` counts unfinished items, the other groups count rows. */
function countText(section: (typeof SECTIONS)[number], group: TodoGroupView): string {
  return section.key === 'mine' ? `${group.count} 个未完成项` : `${group.count} 项`
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader
      title="待办"
      description="按你的角色与数据范围计算；只列出当前轮到你处理的事项。"
    >
      <span v-if="board" class="weak small">共 {{ total }} 项</span>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
      style="margin-bottom: 16px"
    />

    <div v-if="loading && !board" class="panel panel-pad">
      <el-skeleton :rows="6" animated />
    </div>

    <template v-else-if="board">
      <div v-if="total === 0" class="panel todo-empty">
        <EmptyState
          title="当前没有待办"
          hint="你的待测试、审批、执行与发起的任务都没有待处理事项。"
        />
      </div>

      <template v-else>
        <div class="todo-grid">
          <DataSection
            v-for="section in SECTIONS"
            :key="section.key"
            class="todo-section"
            :title="section.title"
            :subtitle="countText(section, groups[section.key])"
            flush
          >
            <ul v-if="groups[section.key].items.length" class="todo-list">
              <li v-for="row in groups[section.key].items" :key="`${row.objectType}:${row.objectId}`">
                <component
                  :is="row.link ? RouterLink : 'div'"
                  :to="row.link || undefined"
                  class="todo-row"
                  :class="{ 'todo-row-static': !row.link }"
                  :aria-label="
                    row.link
                      ? `${typeLabel(row.objectType)} ${row.label}，${row.statusLabel || row.status}，查看详情`
                      : undefined
                  "
                >
                  <span class="todo-kind">{{ typeLabel(row.objectType) }}</span>
                  <span class="todo-label mono" :title="row.label">{{ row.label }}</span>
                  <span class="todo-chip" :class="`chip-${statusTone(row.status)}`">
                    {{ row.statusLabel || row.status }}
                  </span>
                  <span class="todo-wait" :title="waitingTitle(row)">
                    {{ waitingText(row.waitingHours) }}
                  </span>
                  <el-icon v-if="row.link" class="todo-go"><ArrowRight /></el-icon>
                </component>
              </li>
            </ul>

            <div
              v-if="groups[section.key].count > groups[section.key].items.length"
              class="todo-more"
            >
              <template v-if="section.key === 'mine'">
                共 {{ groups[section.key].count }} 个未完成变更项，仅列出等待最久的
                {{ groups[section.key].items.length }} 个任务。
              </template>
              <template v-else>
                共 {{ groups[section.key].count }} 项，仅显示等待最久的
                {{ groups[section.key].items.length }} 项。
              </template>
            </div>

            <EmptyState v-if="!groups[section.key].items.length" :title="section.emptyTitle" />
          </DataSection>
        </div>

        <p class="todo-scope weak small">
          列表由服务端按你的角色、任务成员关系与部署单元数据范围计算；页面不做二次过滤，也不提供忽略/已读状态。
        </p>
      </template>
    </template>
  </div>
</template>

<style scoped>
.todo-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}

.todo-empty {
  margin-bottom: 0;
}

.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.todo-list li + li {
  border-top: 1px solid var(--border);
}

.todo-row {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto auto 14px;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
  color: inherit;
  font-size: 13px;
  line-height: 1.4;
}

a.todo-row:hover {
  background: var(--surface-3);
  text-decoration: none;
}

.todo-row-static {
  cursor: default;
}

.todo-kind {
  color: var(--text-3);
  font-size: 11px;
  white-space: nowrap;
}

.todo-label {
  color: var(--text);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-chip {
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

.chip-accent {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border));
  background: var(--accent-weak);
}

.todo-wait {
  color: var(--text-2);
  font-size: 12px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.todo-go {
  color: var(--text-3);
  font-size: 12px;
}

.todo-more {
  padding: 8px 16px;
  border-top: 1px solid var(--border);
  color: var(--text-3);
  font-size: 12px;
}

.todo-scope {
  margin: 16px 2px 0;
}

@media (max-width: 1100px) {
  .todo-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>