<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, unwrap } from '../api/client'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'

interface Product { id: number; productCode: string; name: string; description?: string; status: string }
interface Plan { id: number; productId: number; planCode: string; name: string; description?: string; status: string; version: number }
interface Price { id: number; planId: number; currency: string; billingPeriod: string }
interface PriceVersion { id: number; priceId: number; versionNo: number; baseAmountMinor: number; status: string; effectiveAt?: string; revision: number }
interface Meter { id: number; productId: number; meterCode: string; name: string; unit: string }
interface Model { id:number; modelCode:string; name:string; meterIds:number[] }
interface UsageRule { id: number; meterId: number; meterCode: string; mode: string; includedQuantity: number; unitAmountMinor?: number; tiers: unknown[] }

const route = useRoute()
const router = useRouter()
const section = computed(() => String(route.query.section || 'catalog'))
const products = ref<Product[]>([])
const plans = ref<Plan[]>([])
const prices = ref<Price[]>([])
const versions = ref<PriceVersion[]>([])
const meters = ref<Meter[]>([])
const models = ref<Model[]>([])
const rules = ref<UsageRule[]>([])
const selectedProduct = ref<Product | null>(null)
const selectedPlan = ref<Plan | null>(null)
const selectedPrice = ref<Price | null>(null)
const selectedVersion = ref<PriceVersion | null>(null)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const planForm = reactive({ planCode: '', name: '', description: '', monthlyPrice: 0 })
const meterVisible=ref(false);const modelVisible=ref(false)
const meterForm=reactive({meterCode:'',name:'',unit:'REQUEST'})
const modelForm=reactive({modelCode:'',name:'',meterIds:[] as number[]})

const title = computed(() => ({ catalog: '产品目录', prices: '价格版本', rules: '计费规则' }[section.value] || '产品目录'))
const subtitle = computed(() => ({
  catalog: '统一维护平台产品与套餐；新增套餐和取消旧套餐均在本页完成。',
  prices: '查看套餐的计费周期、币种和已发布价格版本。',
  rules: '查看产品计量项，以及价格版本关联的用量计费规则。',
}[section.value] || '平台产品与定价管理'))

function money(value: number, currency = 'CNY') {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format((value || 0) / 100)
}

async function chooseProduct(row: Product | null) {
  if (!row) return
  selectedProduct.value = row
  selectedPlan.value = null
  selectedPrice.value = null
  selectedVersion.value = null
  prices.value = []
  versions.value = []
  rules.value = []
  const [planPage, meterList, modelList] = await Promise.all([
    api.get(`/products/${row.id}/plans`, { params: { page: 1, size: 100 } }),
    api.get(`/products/${row.id}/meters`),
    api.get(`/platform/products/${row.id}/models`),
  ])
  plans.value = unwrap<{ records: Plan[] }>(planPage).records
  meters.value = unwrap<Meter[]>(meterList)
  models.value = unwrap<Model[]>(modelList)
  if (plans.value[0]) await choosePlan(plans.value[0])
}

async function choosePlan(row: Plan | null) {
  if (!row || !selectedProduct.value) return
  selectedPlan.value = row
  selectedPrice.value = null
  selectedVersion.value = null
  versions.value = []
  rules.value = []
  const page = unwrap<{ records: Price[] }>(await api.get(
    `/products/${selectedProduct.value.id}/plans/${row.id}/prices`,
    { params: { page: 1, size: 100 } },
  ))
  prices.value = page.records
  if (prices.value[0]) await choosePrice(prices.value[0])
}

async function choosePrice(row: Price | null) {
  if (!row || !selectedProduct.value || !selectedPlan.value) return
  selectedPrice.value = row
  selectedVersion.value = null
  rules.value = []
  const page = unwrap<{ records: PriceVersion[] }>(await api.get(
    `/products/${selectedProduct.value.id}/plans/${selectedPlan.value.id}/prices/${row.id}/versions`,
    { params: { page: 1, size: 100 } },
  ))
  versions.value = page.records
  if (versions.value[0]) await chooseVersion(versions.value[0])
}

async function chooseVersion(row: PriceVersion | null) {
  if (!row || !selectedProduct.value || !selectedPlan.value || !selectedPrice.value) return
  selectedVersion.value = row
  rules.value = unwrap<UsageRule[]>(await api.get(
    `/products/${selectedProduct.value.id}/plans/${selectedPlan.value.id}/prices/${selectedPrice.value.id}/versions/${row.id}/usage-rules`,
  ))
}

async function load() {
  loading.value = true
  try {
    const page = unwrap<{ records: Product[] }>(await api.get('/products', { params: { page: 1, size: 100 } }))
    products.value = page.records
    if (products.value[0]) await chooseProduct(products.value[0])
  } finally {
    loading.value = false
  }
}

function openCreatePlan() {
  if (!selectedProduct.value) return ElMessage.warning('请先选择产品')
  Object.assign(planForm, { planCode: '', name: '', description: '', monthlyPrice: 0 })
  dialogVisible.value = true
}

async function savePlan(publish: boolean) {
  if (!selectedProduct.value || !planForm.planCode.trim() || !planForm.name.trim()) {
    return ElMessage.warning('请填写套餐编码和中文名称')
  }
  if (publish && planForm.monthlyPrice <= 0) return ElMessage.warning('发布套餐时月费必须大于 0')
  saving.value = true
  try {
    const productId = selectedProduct.value.id
    const planId = unwrap<number>(await api.post(`/products/${productId}/plans`, {
      planCode: planForm.planCode.trim().toUpperCase(),
      name: planForm.name.trim(),
      description: planForm.description.trim() || null,
    }))
    if (publish) {
      const priceId = unwrap<number>(await api.post(`/products/${productId}/plans/${planId}/prices`, {
        currency: 'CNY', billingPeriod: 'MONTHLY',
      }))
      const versionId = unwrap<number>(await api.post(`/products/${productId}/plans/${planId}/prices/${priceId}/versions`, {
        baseAmountMinor: Math.round(planForm.monthlyPrice * 100),
      }))
      await api.post(`/products/${productId}/plans/${planId}/prices/${priceId}/versions/${versionId}/publish`, {
        effectiveAt: new Date().toISOString(), revision: 0,
      })
      await api.post(`/products/${productId}/plans/${planId}/publish`)
    }
    dialogVisible.value = false
    await chooseProduct(selectedProduct.value)
    ElMessage.success(publish ? '新套餐已新增并发布' : '新套餐已保存为草稿')
  } finally {
    saving.value = false
  }
}

async function retirePlan(row: Plan) {
  if (!selectedProduct.value) return
  try {
    await ElMessageBox.confirm(
      `取消“${row.name}”后将不能用于新订阅，历史订阅和账单仍会保留。`,
      '确认取消套餐',
      { confirmButtonText: '确认取消', cancelButtonText: '返回', type: 'warning' },
    )
  } catch {
    return
  }
  await api.post(`/products/${selectedProduct.value.id}/plans/${row.id}/retire`)
  await chooseProduct(selectedProduct.value)
  ElMessage.success('套餐已取消并归档')
}

async function saveMeter(){if(!selectedProduct.value)return;saving.value=true;try{await api.post(`/products/${selectedProduct.value.id}/meters`,{meterCode:meterForm.meterCode.trim().toUpperCase(),name:meterForm.name.trim(),unit:meterForm.unit.trim().toUpperCase()});meterVisible.value=false;await chooseProduct(selectedProduct.value);ElMessage.success('计量项已创建')}finally{saving.value=false}}
async function saveModel(){if(!selectedProduct.value)return;saving.value=true;try{await api.post(`/platform/products/${selectedProduct.value.id}/models`,{modelCode:modelForm.modelCode.trim(),name:modelForm.name.trim(),meterIds:modelForm.meterIds});modelVisible.value=false;await chooseProduct(selectedProduct.value);ElMessage.success('产品模型及计量项绑定已创建')}finally{saving.value=false}}

function changeSection(value: string) {
  router.replace({ path: '/catalog', query: value === 'catalog' ? {} : { section: value } })
}

onMounted(load)
</script>

<template>
  <MBPageHeader :title="title" :subtitle="subtitle">
    <template #actions>
      <el-button type="primary" :icon="Plus" :disabled="!selectedProduct" @click="openCreatePlan">新增套餐</el-button>
    </template>
  </MBPageHeader>

  <div class="section-tabs" role="tablist" aria-label="产品与定价模块">
    <button :class="{ active: section === 'catalog' }" @click="changeSection('catalog')">产品目录</button>
    <button :class="{ active: section === 'prices' }" @click="changeSection('prices')">价格版本</button>
    <button :class="{ active: section === 'rules' }" @click="changeSection('rules')">计费规则</button>
  </div>

  <div v-loading="loading" class="catalog-workspace">
    <section class="mb-card table-card product-panel">
      <header class="mb-card-title"><strong>产品目录</strong><span>{{ products.length }} 个产品</span></header>
      <el-table :data="products" highlight-current-row row-key="id" @current-change="chooseProduct">
        <el-table-column prop="productCode" label="产品编码" min-width="168" />
        <el-table-column prop="name" label="名称" min-width="132" />
        <el-table-column label="状态" width="86"><template #default="{ row }"><MBStatusTag :status="row.status" /></template></el-table-column>
      </el-table>
    </section>

    <section v-if="section === 'catalog'" class="mb-card table-card detail-panel">
      <header class="mb-card-title">
        <div><strong>{{ selectedProduct?.name || '套餐管理' }}</strong><small>套餐管理</small></div>
        <el-button type="primary" plain :icon="Plus" :disabled="!selectedProduct" @click="openCreatePlan">新增套餐</el-button>
      </header>
      <el-table :data="plans" highlight-current-row row-key="id" @current-change="choosePlan">
        <el-table-column prop="planCode" label="套餐编码" min-width="160" show-overflow-tooltip />
        <el-table-column prop="name" label="套餐名称" min-width="120" />
        <el-table-column prop="description" label="说明" min-width="130" show-overflow-tooltip />
        <el-table-column label="状态" width="80"><template #default="{ row }"><MBStatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'RETIRED'" link type="danger" @click.stop="retirePlan(row)">取消套餐</el-button>
            <span v-else class="muted">已归档</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="selectedProduct && !plans.length" description="该产品还没有套餐，请点击右上角新增" />
    </section>

    <section v-else-if="section === 'prices'" class="detail-stack">
      <section class="mb-card table-card">
        <header class="mb-card-title"><strong>{{ selectedPlan?.name || '价格定义' }}</strong><span>选择套餐后查看</span></header>
        <div class="selector-row">
          <el-select :model-value="selectedPlan?.id" placeholder="选择套餐" @change="(id:number) => choosePlan(plans.find(item => item.id === id) || null)">
            <el-option v-for="item in plans" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </div>
        <el-table :data="prices" highlight-current-row @current-change="choosePrice">
          <el-table-column prop="id" label="价格 ID" width="100" />
          <el-table-column prop="currency" label="币种" />
          <el-table-column label="计费周期"><template #default="{ row }">{{ row.billingPeriod === 'MONTHLY' ? '按月' : '按年' }}</template></el-table-column>
        </el-table>
      </section>
      <section class="mb-card table-card">
        <header class="mb-card-title"><strong>价格版本</strong><span>{{ versions.length }} 个版本</span></header>
        <el-table :data="versions" highlight-current-row @current-change="chooseVersion">
          <el-table-column prop="versionNo" label="版本" width="88" />
          <el-table-column label="基础价格" min-width="130"><template #default="{ row }">{{ money(row.baseAmountMinor, selectedPrice?.currency) }}</template></el-table-column>
          <el-table-column prop="effectiveAt" label="生效时间" min-width="190" />
          <el-table-column label="状态" width="100"><template #default="{ row }"><MBStatusTag :status="row.status" /></template></el-table-column>
        </el-table>
      </section>
    </section>

    <section v-else class="detail-stack">
      <section class="mb-card table-card">
        <header class="mb-card-title"><strong>计量项</strong><el-button link type="primary" @click="Object.assign(meterForm,{meterCode:'',name:'',unit:'REQUEST'});meterVisible=true">新增计量项</el-button></header>
        <el-table :data="meters">
          <el-table-column prop="meterCode" label="计量编码" min-width="170" />
          <el-table-column prop="name" label="中文名称" min-width="140" />
          <el-table-column prop="unit" label="单位" width="110" />
        </el-table>
      </section>
      <section class="mb-card table-card">
        <header class="mb-card-title"><strong>产品模型</strong><el-button link type="primary" :disabled="!meters.length" @click="Object.assign(modelForm,{modelCode:'',name:'',meterIds:[]});modelVisible=true">新增模型</el-button></header>
        <el-table :data="models"><el-table-column prop="modelCode" label="模型编码"/><el-table-column prop="name" label="模型名称"/><el-table-column label="绑定计量项"><template #default="{row}">{{ row.meterIds.map((id:number)=>meters.find(m=>m.id===id)?.meterCode||id).join('、') }}</template></el-table-column></el-table>
      </section>
      <section class="mb-card table-card">
        <header class="mb-card-title"><strong>计费规则</strong><span>{{ selectedVersion ? `价格版本 V${selectedVersion.versionNo}` : '暂无价格版本' }}</span></header>
        <el-table :data="rules">
          <el-table-column prop="meterCode" label="计量编码" min-width="150" />
          <el-table-column prop="mode" label="计价方式" min-width="110" />
          <el-table-column prop="includedQuantity" label="包含用量" min-width="110" />
          <el-table-column label="单价"><template #default="{ row }">{{ row.unitAmountMinor == null ? '阶梯定价' : money(row.unitAmountMinor, selectedPrice?.currency) }}</template></el-table-column>
        </el-table>
        <el-empty v-if="!rules.length" description="所选价格版本暂未配置用量计费规则" />
      </section>
    </section>
  </div>

  <el-dialog v-model="dialogVisible" title="新增套餐" width="520px" destroy-on-close>
    <el-form label-position="top">
      <div class="form-grid">
        <el-form-item label="套餐编码（唯一）" required><el-input v-model="planForm.planCode" maxlength="64" placeholder="例如 STANDARD_MONTHLY" /></el-form-item>
        <el-form-item label="套餐中文名称" required><el-input v-model="planForm.name" maxlength="128" placeholder="例如 标准版" /></el-form-item>
      </div>
      <el-form-item label="套餐说明"><el-input v-model="planForm.description" type="textarea" :rows="3" maxlength="512" placeholder="说明适用客户和包含的能力" /></el-form-item>
      <el-form-item label="月费（人民币元）"><el-input-number v-model="planForm.monthlyPrice" :min="0" :precision="2" :step="100" controls-position="right" /></el-form-item>
      <p class="form-tip">保存草稿可稍后完善；保存并发布会同时创建人民币月价并立即生效。</p>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button :loading="saving" @click="savePlan(false)">保存草稿</el-button>
      <el-button type="primary" :loading="saving" @click="savePlan(true)">保存并发布</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="meterVisible" title="新增计量项" width="500px"><el-form label-position="top"><el-form-item label="计量编码" required><el-input v-model="meterForm.meterCode" placeholder="例如 INPUT_TOKENS"/></el-form-item><el-form-item label="名称" required><el-input v-model="meterForm.name"/></el-form-item><el-form-item label="单位" required><el-input v-model="meterForm.unit" placeholder="TOKEN / REQUEST"/></el-form-item></el-form><template #footer><el-button @click="meterVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMeter">创建</el-button></template></el-dialog>
  <el-dialog v-model="modelVisible" title="新增产品模型" width="540px"><el-form label-position="top"><el-form-item label="模型编码" required><el-input v-model="modelForm.modelCode" placeholder="例如 gpt-6-mini"/></el-form-item><el-form-item label="模型名称" required><el-input v-model="modelForm.name"/></el-form-item><el-form-item label="绑定计量项" required><el-select v-model="modelForm.meterIds" multiple style="width:100%"><el-option v-for="m in meters" :key="m.id" :value="m.id" :label="`${m.name} (${m.meterCode})`"/></el-select></el-form-item></el-form><template #footer><el-button @click="modelVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveModel">创建并绑定</el-button></template></el-dialog>
</template>

<style scoped>
.section-tabs{display:flex;gap:28px;height:42px;margin:-4px 0 12px;padding:0 16px;background:#fff;border:1px solid var(--mb-line);border-radius:8px}.section-tabs button{position:relative;border:0;background:transparent;color:#66748e;font:inherit;cursor:pointer}.section-tabs button.active{color:var(--mb-green-dark);font-weight:700}.section-tabs button.active::after{content:"";position:absolute;right:0;bottom:0;left:0;height:3px;border-radius:3px 3px 0 0;background:var(--mb-green)}
.catalog-workspace{display:grid;grid-template-columns:minmax(430px,.76fr) minmax(620px,1.24fr);gap:12px;align-items:start}.product-panel,.detail-panel{min-height:420px}.detail-stack{display:grid;gap:12px}.mb-card-title>div{display:flex;align-items:baseline;gap:10px}.mb-card-title small,.mb-card-title>span{color:var(--mb-muted);font-size:12px;font-weight:400}.selector-row{padding:12px 14px;border-bottom:1px solid var(--mb-soft-line)}.selector-row .el-select{width:280px}.muted{color:var(--mb-muted);font-size:12px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.form-tip{margin:-2px 0 0;color:var(--mb-muted);font-size:12px;line-height:1.6}
@media (max-width:1100px){.catalog-workspace{grid-template-columns:1fr}.product-panel,.detail-panel{min-height:auto}}
</style>
