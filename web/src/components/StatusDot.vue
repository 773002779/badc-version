<script setup lang="ts">
import { computed } from 'vue'
import { isBusyStatus, statusLabel, statusTone } from '../utils/format'
import type { StatusDomain } from '../utils/format'

const props = withDefaults(
  defineProps<{
    status?: string | null
    label?: string
    pulse?: boolean
    dotOnly?: boolean
    domain?: StatusDomain
  }>(),
  { pulse: false, dotOnly: false, domain: 'item' }
)

const tone = computed(() => statusTone(props.status))
const text = computed(() => props.label ?? statusLabel(props.status, props.domain))
const pulsing = computed(() => props.pulse && isBusyStatus(props.status))
</script>

<template>
  <span class="status" :class="`tone-${tone}`">
    <i class="dot" :class="{ pulsing }" aria-hidden="true" />
    <span v-if="!dotOnly" class="text">{{ text }}</span>
  </span>
</template>

<style scoped>
.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-2);
  white-space: nowrap;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--text-3);
  flex: none;
}
.tone-accent .dot {
  background: var(--accent);
}
.tone-accent .text {
  color: var(--text);
}
.tone-success .dot {
  background: #12b76a;
}
.tone-success .text {
  color: var(--text);
}
.tone-danger .dot {
  background: var(--danger);
}
.tone-danger .text {
  color: var(--danger);
}
.tone-warn .dot {
  background: var(--warn);
}
.tone-warn .text {
  color: var(--warn);
}
.pulsing {
  animation: pulse 1.8s ease-in-out infinite;
}
@keyframes pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 var(--accent-ring);
  }
  50% {
    box-shadow: 0 0 0 4px transparent;
  }
}
@media (prefers-reduced-motion: reduce) {
  .pulsing {
    animation: none;
  }
}
</style>