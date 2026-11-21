<script setup lang="ts">
defineProps<{
  title: string
  subtitle?: string
  flush?: boolean
}>()
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <span class="panel-title">{{ title }}</span>
      <span v-if="subtitle" class="weak small">{{ subtitle }}</span>
      <div class="panel-actions">
        <slot name="actions" />
      </div>
    </div>
    <div :class="flush ? '' : 'panel-pad'">
      <slot />
    </div>
  </section>
</template>