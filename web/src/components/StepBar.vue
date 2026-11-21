<script setup lang="ts">
import { computed } from 'vue'
import type { ReleaseNode } from '../api/types'
import { formatDuration, statusLabel } from '../utils/format'

const props = defineProps<{ nodes: ReleaseNode[] }>()

const steps = computed(() =>
  props.nodes.map((node) => {
    const started = node.startedAt ? new Date(node.startedAt).getTime() : null
    const finished = node.finishedAt ? new Date(node.finishedAt).getTime() : null
    return {
      type: node.nodeType,
      label: statusLabel(node.nodeType),
      status: node.status,
      text: statusLabel(node.status, 'node'),
      duration: started && finished ? formatDuration(finished - started) : null
    }
  })
)

function markClass(status: string) {
  if (status === 'SUCCESS') return 'is-done'
  if (status === 'FAILED' || status === 'TIMEOUT') return 'is-failed'
  if (status === 'RUNNING') return 'is-current'
  if (status === 'SKIPPED') return 'is-skipped'
  return 'is-pending'
}
</script>

<template>
  <ol class="steps">
    <li v-for="(s, i) in steps" :key="s.type" class="step">
      <div class="line" :class="{ first: i === 0, last: i === steps.length - 1 }" />
      <div class="mark" :class="markClass(s.status)">
        <span v-if="s.status === 'SUCCESS'" class="tick">✓</span>
        <span v-else-if="s.status === 'FAILED' || s.status === 'TIMEOUT'" class="cross">✕</span>
        <span v-else class="index">{{ i + 1 }}</span>
      </div>
      <div class="label">{{ s.label }}</div>
      <div class="sub">
        {{ s.text }}
        <span v-if="s.duration" class="weak">· {{ s.duration }}</span>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.steps {
  display: flex;
  list-style: none;
  margin: 0;
  padding: 8px 0 4px;
}
.step {
  position: relative;
  flex: 1;
  min-width: 0;
  text-align: center;
}
.line {
  position: absolute;
  top: 13px;
  left: -50%;
  width: 100%;
  height: 1px;
  background: var(--border);
}
.line.first {
  display: none;
}
.mark {
  position: relative;
  z-index: 1;
  width: 28px;
  height: 28px;
  margin: 0 auto 8px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  font-size: 12px;
  border: 1px solid var(--border-strong);
  background: var(--surface-2);
  color: var(--text-3);
}
.mark.is-done {
  border-color: var(--accent);
  background: var(--accent-weak);
  color: var(--accent);
}
.mark.is-current {
  border-color: var(--accent);
  color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}
.mark.is-failed {
  border-color: var(--danger);
  color: var(--danger);
  background: var(--danger-weak);
}
.mark.is-skipped {
  border-style: dashed;
}
.label {
  font-size: 13px;
  color: var(--text);
}
.sub {
  font-size: 12px;
  color: var(--text-3);
}
</style>