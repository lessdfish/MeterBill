<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api,unwrap } from '../api/client'
import { useAuthStore } from '../stores/auth'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'

const auth=useAuthStore(); const members=ref<any[]>([]); const audits=ref<any[]>([]); const loading=ref(false); const saving=ref(false)
const addDialog=ref(false); const addEmail=ref(''); const addRole=ref('VIEWER')
const roleDialog=ref(false); const selected=ref<any>(null); const nextRole=ref('VIEWER')
const canManage=computed(()=>auth.hasPermission('MEMBER_ROLE_MANAGE'))
const date=(value?:string)=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—'
function protectedMember(row:any){return row.userId===auth.profile?.userId||row.roles.includes('OWNER')}
async function load(){loading.value=true;try{[members.value,audits.value]=await Promise.all([api.get('/tenant/security/members').then(r=>unwrap<any[]>(r)),api.get('/tenant/security/audit',{params:{limit:50}}).then(r=>unwrap<any[]>(r))])}finally{loading.value=false}}
function prepareRole(row:any){selected.value=row;nextRole.value=row.roles[0]==='ADMIN'?'VIEWER':'ADMIN';roleDialog.value=true}
async function addMember(){saving.value=true;try{await api.post('/tenant/security/members',{email:addEmail.value.trim(),roleCode:addRole.value});ElMessage.success('成员已加入当前租户');addDialog.value=false;addEmail.value='';addRole.value='VIEWER';await load()}finally{saving.value=false}}
async function changeRole(){saving.value=true;try{await api.put(`/tenant/security/members/${selected.value.memberId}/role`,{roleCode:nextRole.value});ElMessage.success('成员角色已更新');roleDialog.value=false;await load()}finally{saving.value=false}}
async function disable(row:any){await ElMessageBox.confirm(`停用后，${row.displayName} 将无法访问当前租户，但全局账号不会被删除。`,'确认停用成员',{type:'warning',confirmButtonText:'确认停用',cancelButtonText:'取消'});saving.value=true;try{await api.post(`/tenant/security/members/${row.memberId}/disable`);ElMessage.success('成员已停用');await load()}finally{saving.value=false}}
onMounted(load)
</script>
<template>
  <MBPageHeader title="成员与审计" subtitle="管理当前租户的已有用户、角色与访问状态；全部变更写入审计记录。"><template #actions><el-button type="primary" :disabled="!canManage" @click="addDialog=true">添加已有用户</el-button></template></MBPageHeader>
  <div class="security-grid" v-loading="loading"><section class="mb-card table-card"><header class="mb-card-title"><strong>成员与角色</strong><span>{{ members.length }} 人</span></header><el-table :data="members"><el-table-column prop="displayName" label="成员" min-width="140"/><el-table-column prop="email" label="邮箱" min-width="220"/><el-table-column label="角色" min-width="150"><template #default="{row}"><el-tag v-for="role in row.roles" :key="role" class="role-tag" effect="plain">{{ role }}</el-tag><span v-if="!row.roles.length">—</span></template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}"><MBStatusTag :status="row.status"/></template></el-table-column><el-table-column label="操作" width="150"><template #default="{row}"><el-button text type="primary" :disabled="!canManage||protectedMember(row)||row.status!=='ACTIVE'" @click="prepareRole(row)">改角色</el-button><el-button text type="danger" :disabled="!canManage||protectedMember(row)||row.status!=='ACTIVE'||saving" @click="disable(row)">停用</el-button></template></el-table-column></el-table></section><section class="mb-card table-card"><header class="mb-card-title"><strong>操作审计</strong><span>最近 50 条</span></header><el-table :data="audits"><el-table-column label="时间" min-width="180"><template #default="{row}">{{ date(row.createdAt) }}</template></el-table-column><el-table-column prop="action" label="操作" min-width="180"/><el-table-column prop="targetType" label="对象" width="120"/><el-table-column prop="targetId" label="对象ID" min-width="130"/><el-table-column label="结果" width="100"><template #default="{row}"><MBStatusTag :status="row.outcome==='SUCCESS'?'SUCCEEDED':'FAILED'"/></template></el-table-column></el-table></section></div>
  <el-dialog v-model="addDialog" title="添加已有用户" width="450px"><el-alert title="当前没有邮件邀请基础设施，只能添加系统中已存在且有效的账号。" type="info" :closable="false"/><el-form label-position="top" class="member-form"><el-form-item label="用户邮箱"><el-input v-model="addEmail" type="email" placeholder="name@example.com"/></el-form-item><el-form-item label="租户角色"><el-radio-group v-model="addRole"><el-radio-button value="VIEWER">只读成员</el-radio-button><el-radio-button value="ADMIN">租户管理员</el-radio-button></el-radio-group></el-form-item></el-form><template #footer><el-button @click="addDialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!addEmail.trim()" @click="addMember">确认添加</el-button></template></el-dialog>
  <el-dialog v-model="roleDialog" title="修改租户角色" width="430px"><p>成员：<strong>{{ selected?.displayName }}</strong></p><el-radio-group v-model="nextRole"><el-radio-button value="VIEWER">只读成员</el-radio-button><el-radio-button value="ADMIN">租户管理员</el-radio-button></el-radio-group><template #footer><el-button @click="roleDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="changeRole">确认修改</el-button></template></el-dialog>
</template>
<style scoped>.security-grid{display:grid;grid-template-columns:minmax(560px,.95fr) minmax(620px,1.05fr);gap:12px}.role-tag{margin-right:5px}.member-form{margin-top:18px}@media(max-width:1400px){.security-grid{grid-template-columns:1fr}}</style>
