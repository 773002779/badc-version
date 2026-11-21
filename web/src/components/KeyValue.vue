<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value?: string | number | null
    mono?: boolean
    wide?: boolean
  }>(),
  { mono: false, wide: false }
)
</script>

<template>
  <div class="kv" :class="{ wide }">
    <div class="k">{{ label }}</div>
    <div class="v" :class="{ mono }">
      <slot>{{ value === null || value === undefined || value === '' ? '—' : value }}</slot>
    </div>
  </div>
</template>

<style scoped>
.kv {
  display: grid;
  grid-template-columns: 128px 1fr;
  gap: 12px;
  align-items: baseline;
  padding: 9px 0;
  border-bottom: 1px dashed var(--border);
}
.kv:last-child {
  border-bottom: none;
}
.kv.wide {
  grid-template-columns: 1fr;
  gap: 4px;
}
.k {
  font-size: 12px;
  color: var(--text-3);
}
.v {
  font-size: 13px;
  color: var(--text);
  min-width: 0;
  word-break: break-word;
}
</style>