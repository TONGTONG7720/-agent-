<template>
  <div>
    <h2>系统管理</h2>
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-head">
              <span>🧩 模型管理</span>
              <el-button size="small" type="primary" @click="modelDlg = true">新增模型</el-button>
            </div>
          </template>
          <el-table :data="models" size="small">
            <el-table-column prop="name" label="名称" width="110" />
            <el-table-column prop="litellmModelName" label="网关模型名" />
            <el-table-column prop="apiKeyMasked" label="Key" width="130" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card>
          <template #header>🎭 Agent 角色配置</template>
          <el-table :data="roleConfigs" size="small">
            <el-table-column label="角色" width="120">
              <template #default="{ row }">
                {{ AGENT_META[row.role]?.icon }} {{ AGENT_META[row.role]?.name ?? row.role }}
              </template>
            </el-table-column>
            <el-table-column label="默认模型" width="160">
              <template #default="{ row }">
                <el-select :model-value="row.defaultModelId" size="small" clearable placeholder="内置默认"
                           @update:model-value="(v: number | null) => onUpdateRole(row, row.systemPrompt, v ?? null)">
                  <el-option v-for="m in models" :key="m.id" :value="m.id" :label="m.name" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="提示词">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openPromptDlg(row)">
                  {{ row.systemPrompt ? '已自定义' : '内置默认' }} · 编辑
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="modelDlg" title="新增模型" width="480px">
      <el-form label-width="110px">
        <el-form-item label="显示名称">
          <el-input v-model="newModel.name" placeholder="如：通义Plus" />
        </el-form-item>
        <el-form-item label="网关模型名">
          <el-input v-model="newModel.litellmModelName" placeholder="litellm-config.yaml 中注册的 model_name" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="newModel.apiKey" placeholder="可选，仅登记用（实际调用走网关配置）" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDlg = false">取消</el-button>
        <el-button type="primary" @click="onCreateModel">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="promptDlg" :title="`编辑提示词 · ${AGENT_META[editingRole?.role ?? '']?.name ?? ''}`" width="640px">
      <el-input v-model="editingPrompt" type="textarea" :rows="10"
                placeholder="留空则使用 Agent 服务内置默认提示词" />
      <template #footer>
        <el-button @click="promptDlg = false">取消</el-button>
        <el-button type="primary" @click="onSavePrompt">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type LlmModelView, type RoleConfig } from '../api'
import { AGENT_META } from '../utils/status'

const models = ref<LlmModelView[]>([])
const roleConfigs = ref<RoleConfig[]>([])
const modelDlg = ref(false)
const newModel = reactive({ name: '', litellmModelName: '', apiKey: '' })
const promptDlg = ref(false)
const editingRole = ref<RoleConfig | null>(null)
const editingPrompt = ref('')

async function load() {
  models.value = await api.listModels()
  roleConfigs.value = await api.listRoleConfigs()
}

async function onCreateModel() {
  if (!newModel.name.trim() || !newModel.litellmModelName.trim()) {
    ElMessage.warning('请填写名称与网关模型名')
    return
  }
  await api.createModel(newModel.name.trim(), newModel.litellmModelName.trim(), newModel.apiKey)
  modelDlg.value = false
  Object.assign(newModel, { name: '', litellmModelName: '', apiKey: '' })
  await load()
}

function openPromptDlg(row: RoleConfig) {
  editingRole.value = row
  editingPrompt.value = row.systemPrompt ?? ''
  promptDlg.value = true
}

async function onSavePrompt() {
  if (!editingRole.value) return
  await onUpdateRole(editingRole.value,
    editingPrompt.value.trim() || null, editingRole.value.defaultModelId)
  promptDlg.value = false
}

async function onUpdateRole(row: RoleConfig, systemPrompt: string | null, defaultModelId: number | null) {
  await api.updateRoleConfig(row.role, systemPrompt, defaultModelId)
  await load()
  ElMessage.success('已保存')
}

onMounted(load)
</script>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
