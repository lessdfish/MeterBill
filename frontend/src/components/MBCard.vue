<script setup lang="ts">
defineProps<{ title?: string }>()
</script>
<template><section class="mb-card"><header v-if="title || $slots.action" class="mb-card-title"><strong>{{ title }}</strong><slot name="action" /></header><div class="mb-card-body"><slot /></div></section></template>
