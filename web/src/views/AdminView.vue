<template>
  <div>
    <div class="page-head">
      <div>
        <h2>系统管理</h2>
        <div class="sub">模型接入 · Agent 流水线编排</div>
      </div>
    </div>

    <el-card style="margin-bottom:20px">
      <template #header>
        <div class="card-head">
          <span>模型管理</span>
          <el-button size="small" type="primary" @click="modelDlg = true">新增模型</el-button>
        </div>
      </template>
      <el-table :data="models" size="small">
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="litellmModelName" label="网关模型名" />
        <el-table-column prop="apiKeyMasked" label="Key" width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>Agent 流水线编排 <span class="hint">（拖动顺序即执行顺序，可增删角色）</span></span>
          <el-button size="small" type="primary" @click="openAddDlg">新增角色</el-button>
        </div>
      </template>

      <div class="flow">
        <div v-for="(r, i) in pipeline" :key="r.id" class="flow-node">
          <div class="node-card sticker-card" :class="{ disabled: r.enabled === false }">
            <div class="node-top">
              <span class="node-name">
                <span v-if="AGENT_META[r.role]" class="agent-avatar mini">
                  <img :src="AGENT_META[r.role].avatar" :alt="r.role" />
                </span>
                {{ r.name ?? r.role }}
              </span>
              <span class="kind-chip" :class="r.kind ?? 'analysis'">{{ kindText(r.kind) }}</span>
            </div>
            <div class="node-meta">
              <span class="node-key">{{ r.role }}</span>
              <el-tag v-if="r.hasGate" size="small" type="warning" effect="plain">人审门</el-tag>
              <el-tag v-if="r.kind === 'review' && r.reworkTarget" size="small" type="info" effect="plain">
                返工→{{ r.reworkTarget }}
              </el-tag>
            </div>
            <div class="node-ops">
              <el-button size="small" text @click="move(i, -1)" :disabled="i === 0">↑</el-button>
              <el-button size="small" text @click="move(i, 1)" :disabled="i === pipeline.length - 1">↓</el-button>
              <el-button size="small" text type="primary" @click="openEditDlg(r)">编辑</el-button>
              <el-button size="small" text :type="r.enabled === false ? 'success' : 'info'"
                         @click="toggleEnabled(r)">{{ r.enabled === false ? '启用' : '停用' }}</el-button>
              <el-button size="small" text type="danger" @click="onDelete(r)">删除</el-button>
            </div>
          </div>
          <div v-if="i < pipeline.length - 1" class="flow-arrow">→</div>
        </div>
      </div>
    </el-card>

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

    <el-dialog v-model="roleDlg" :title="editing ? `编辑角色 · ${form.name}` : '新增角色'" width="560px">
      <el-form label-width="96px">
        <el-form-item label="角色 key">
          <el-input v-model="form.role" :disabled="editing" placeholder="英文唯一标识，如 security" />
        </el-form-item>
        <el-form-item label="展示名">
          <el-input v-model="form.name" placeholder="如：安全审计员" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.kind" style="width:100%">
            <el-option value="analysis" label="分析（产文档）" />
            <el-option value="code" label="开发（产代码文件）" />
            <el-option value="test" label="测试（写测试并执行）" />
            <el-option value="review" label="审查（判通过，可返工）" />
          </el-select>
        </el-form-item>
        <el-form-item label="人审门">
          <el-switch v-model="form.hasGate" active-text="该步后需人工确认" />
        </el-form-item>
        <el-form-item v-if="form.kind === 'review'" label="返工目标">
          <el-select v-model="form.reworkTarget" clearable placeholder="失败时回退到哪个角色" style="width:100%">
            <el-option v-for="r in pipeline" :key="r.role" :value="r.role" :label="r.name ?? r.role" />
          </el-select>
        </el-form-item>
        <el-form-item label="提示词">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="6"
                    placeholder="留空则使用内置默认（自定义角色建议填写职责说明）" />
        </el-form-item>
        <el-form-item label="默认模型">
          <el-select v-model="form.defaultModelId" clearable placeholder="内置默认" style="width:100%">
            <el-option v-for="m in models" :key="m.id" :value="m.id" :label="m.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDlg = false">取消</el-button>
        <el-button type="primary" @click="onSaveRole">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type LlmModelView, type RoleConfig } from '../api'
import { AGENT_META } from '../utils/status'

const models = ref<LlmModelView[]>([])
const pipeline = ref<RoleConfig[]>([])
const modelDlg = ref(false)
const newModel = reactive({ name: '', litellmModelName: '', apiKey: '' })

const roleDlg = ref(false)
const editing = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  role: '', name: '', kind: 'analysis', hasGate: false,
  reworkTarget: '' as string | null, systemPrompt: '' as string | null,
  defaultModelId: null as number | null
})

const KIND_TEXT: Record<string, string> = {
  analysis: '分析', code: '开发', test: '测试', review: '审查'
}
function kindText(k: string | null): string {
  return KIND_TEXT[k ?? 'analysis'] ?? k ?? ''
}

async function load() {
  models.value = await api.listModels()
  pipeline.value = await api.listPipeline()
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

function openAddDlg() {
  editing.value = false
  editingId.value = null
  Object.assign(form, {
    role: '', name: '', kind: 'analysis', hasGate: false,
    reworkTarget: '', systemPrompt: '', defaultModelId: null
  })
  roleDlg.value = true
}

function openEditDlg(r: RoleConfig) {
  editing.value = true
  editingId.value = r.id
  Object.assign(form, {
    role: r.role, name: r.name ?? r.role, kind: r.kind ?? 'analysis',
    hasGate: !!r.hasGate, reworkTarget: r.reworkTarget ?? '',
    systemPrompt: r.systemPrompt ?? '', defaultModelId: r.defaultModelId
  })
  roleDlg.value = true
}

async function onSaveRole() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写展示名')
    return
  }
  if (editing.value && editingId.value != null) {
    await api.updateRole(editingId.value, {
      name: form.name.trim(), kind: form.kind, hasGate: form.hasGate,
      reworkTarget: form.reworkTarget || null,
      systemPrompt: form.systemPrompt?.trim() || null, defaultModelId: form.defaultModelId
    })
  } else {
    if (!form.role.trim()) {
      ElMessage.warning('请填写角色 key')
      return
    }
    await api.addRole({
      role: form.role.trim(), name: form.name.trim(), kind: form.kind,
      hasGate: form.hasGate, reworkTarget: form.reworkTarget || undefined
    })
    if (form.systemPrompt?.trim() || form.defaultModelId) {
      const created = (await api.listPipeline()).find(r => r.role === form.role.trim())
      if (created) {
        await api.updateRole(created.id, {
          systemPrompt: form.systemPrompt?.trim() || null, defaultModelId: form.defaultModelId
        })
      }
    }
  }
  roleDlg.value = false
  await load()
  ElMessage.success('已保存')
}

async function toggleEnabled(r: RoleConfig) {
  await api.updateRole(r.id, { enabled: r.enabled === false })
  await load()
}

async function onDelete(r: RoleConfig) {
  await ElMessageBox.confirm(`确定删除角色「${r.name ?? r.role}」？`, '删除角色', { type: 'warning' })
  await api.deleteRole(r.id)
  await load()
  ElMessage.success('已删除')
}

async function move(index: number, dir: -1 | 1) {
  const arr = [...pipeline.value]
  const j = index + dir
  if (j < 0 || j >= arr.length) return
  ;[arr[index], arr[j]] = [arr[j], arr[index]]
  pipeline.value = arr
  await api.reorderPipeline(arr.map(r => r.id))
}

onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: 12px; color: hsl(250 12% 55%); font-weight: 400; }

.flow { display: flex; flex-wrap: wrap; align-items: stretch; gap: 6px; }
.flow-node { display: flex; align-items: center; gap: 6px; }
.node-card { width: 200px; padding: 12px 14px; }
.node-card.disabled { opacity: .5; }
.node-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.node-name { display: inline-flex; align-items: center; gap: 7px; font-weight: 700; font-size: 14px; }
.node-meta { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; min-height: 22px; }
.node-key { font-size: 11px; color: hsl(250 12% 55%); font-family: Consolas, monospace; }
.node-ops { display: flex; flex-wrap: wrap; gap: 2px; }
.flow-arrow { font-size: 20px; color: hsl(var(--c-primary)); font-weight: 700; }

.kind-chip {
  font-size: 11px; padding: 1px 9px; border-radius: var(--radius-pill);
  border: 1.5px solid hsl(var(--c-ink) / .2);
}
.kind-chip.analysis { background: hsl(var(--c-sky) / .12); color: hsl(200 70% 32%); }
.kind-chip.code     { background: hsl(var(--c-primary) / .12); color: hsl(var(--c-primary-deep)); }
.kind-chip.test     { background: hsl(var(--c-mint) / .14); color: hsl(158 60% 28%); }
.kind-chip.review   { background: hsl(var(--c-pink) / .14); color: hsl(20 75% 42%); }

.agent-avatar.mini { width: 24px; height: 24px; }
.agent-avatar.mini img { width: 100%; height: 100%; border-radius: 50%; }
</style>
