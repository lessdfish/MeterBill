<script setup lang="ts">
import { computed } from 'vue'
import { statusMeta } from '../statusCatalog'
const props = defineProps<{ status?: string | null }>()
const meta = computed(() => statusMeta(props.status))
</script>

<template><span class="status-tag" :class="meta.kind" :title="`${meta.code} · ${meta.consequence}`"><span class="status-icon" aria-hidden="true">{{ meta.icon }}</span>{{ meta.label }}<span class="sr-only">。{{ meta.consequence }}</span></span></template>
