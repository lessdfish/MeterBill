<script setup lang="ts">
defineProps<{ title: string; subtitle?: string }>()
</script>
<template><header class="page-header"><div><h1 class="page-title">{{ title }}</h1><p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p></div><div class="page-actions"><slot name="actions"/><slot/></div></header></template>
<style scoped>.page-header{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.page-actions{display:flex;align-items:center;gap:8px;flex:none}</style>
