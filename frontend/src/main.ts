import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElDrawer, ElEmpty,
  ElForm, ElFormItem, ElIcon, ElInput, ElInputNumber, ElOption, ElPagination,
  ElRadioButton, ElRadioGroup, ElSelect, ElTable, ElTableColumn, ElTag, ElTooltip,
} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/app.css'

const app = createApp(App).use(createPinia()).use(router)
for (const component of [
  ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElDrawer, ElEmpty,
  ElForm, ElFormItem, ElIcon, ElInput, ElInputNumber, ElOption, ElPagination,
  ElRadioButton, ElRadioGroup, ElSelect, ElTable, ElTableColumn, ElTag, ElTooltip,
]) app.component(component.name!, component)
app.mount('#app')
