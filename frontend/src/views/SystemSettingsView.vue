<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, unwrap } from '../api/client'
import MBPageHeader from '../components/MBPageHeader.vue'
const loading=ref(false);const saving=ref(false);const form=reactive({tenantCode:'',name:'',billingZoneId:'Asia/Shanghai',version:0})
async function load(){loading.value=true;try{Object.assign(form,unwrap(await api.get('/tenant/settings')))}finally{loading.value=false}}
async function save(){saving.value=true;try{Object.assign(form,unwrap(await api.put('/tenant/settings',{name:form.name,billingZoneId:form.billingZoneId,version:form.version})));ElMessage.success('租户设置已保存')}finally{saving.value=false}}
onMounted(load)
</script>
<template><MBPageHeader title="系统设置" subtitle="配置直接写入当前租户并使用版本号防止并发覆盖。"><template #actions><el-button type="primary" :loading="saving" @click="save">保存设置</el-button></template></MBPageHeader><section v-loading="loading" class="mb-card settings"><el-form label-position="top" :model="form"><el-form-item label="租户编码"><el-input v-model="form.tenantCode" disabled/></el-form-item><el-form-item label="租户名称"><el-input v-model="form.name" maxlength="128" show-word-limit/></el-form-item><el-form-item label="账单时区"><el-select v-model="form.billingZoneId" filterable><el-option v-for="z in ['Asia/Shanghai','Asia/Hong_Kong','Asia/Tokyo','UTC','Europe/London','America/New_York']" :key="z" :value="z" :label="z"/></el-select><div class="hint">自然月账期边界按此 IANA 时区解释。</div></el-form-item></el-form></section></template>
<style scoped>.settings{max-width:720px;padding:24px}.el-select{width:100%}.hint{margin-top:7px;color:#72809b;font-size:12px}</style>
