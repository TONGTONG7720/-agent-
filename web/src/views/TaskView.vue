<template>
  <div v-if="task">
    <div class="head">
      <div>
        <h2 style="margin:0 0 4px">任务 #{{ task.id }}</h2>
        <span class="req">{{ task.requirement }}</span>
      </div>
      <div class="head-right">
        <el-tag :type="statusType(task.status)" size="large">{{ statusText(task.status) }}</el-tag>
        <el-button v-if="!isTerminal" type="danger" plain @click="onCancel">取消任务</el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <!-- 左侧：角色流水线 -->
      <el-col :span="4">
        <el-card>
          <el-steps direction="vertical" :active="activeStep">
            <el-step v-for="r in AGENT_ORDER" :key="r"
                     :title="AGENT_META[r].icon + ' ' + AGENT_META[r].name" />
          </el-steps>
        </el-card>
      </el-col>

      <!-- 中间：事件流 -->
      <el-col :span="13">
        <el-card v-loading="loading" body-style="max-height:70vh;overflow:auto" ref="streamCard">
          <el-empty v-if="store.messages.length === 0" description="等待 Agent 输出…" />
          <div v-for="m in store.messages" :key="m.seq" class="msg">
            <div class="msg-head">
              {{ AGENT_META[m.agent]?.icon ?? '🤖' }} {{ AGENT_META[m.agent]?.name ?? m.agent }}
            </div>
            <pre class="msg-body">{{ m.content }}</pre>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：审批 + 产物 -->
      <el-col :span="7">
        <el-card v-if="store.pendingGate" class="gate-card">
          <template #header>⏸️ {{ GATE_TEXT[store.pendingGate.gate] ?? store.pendingGate.question }}</template>
          <el-input v-model="comment" type="textarea" :rows="3" placeholder="审批意见（驳回时必填）" />
          <div style="margin-top:12px;display:flex;gap:8px">
            <el-button type="success" style="flex:1" :loading="approving" @click="onApprove('pass')">通 过</el-button>
            <el-button type="danger" style="flex:1" :loading="approving" @click="onApprove('reject')">驳 回</el-button>
          </div>
        </el-card>

        <el-card style="margin-top:16px">
          <template #header>📦 产物（{{ artifacts.length }}）</template>
          <el-empty v-if="artifacts.length === 0" description="暂无产物" :image-size="60" />
          <div v-for="a in artifacts" :key="a.id" class="artifact">
            <span>{{ typeIcon(a.type) }} {{ a.name }}</span>
            <el-link type="primary" :href="downloadUrl(a.id)" target="_blank">下载</el-link>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type Artifact, type Task } from '../api'
import { getToken } from '../api/http'
import { useTaskEventsStore } from '../stores/taskEvents'
import { AGENT_META, AGENT_ORDER, GATE_TEXT, statusText, statusType } from '../utils/status'

const route = useRoute()
const store = useTaskEventsStore()
const taskId = Number(route.params.id)
const task = ref<Task | null>(null)
const artifacts = ref<Artifact[]>([])
const comment = ref('')
const approving = ref(false)
const loading = ref(true)

const isTerminal = computed(() =>
  task.value ? ['done', 'failed', 'canceled'].includes(task.value.status) : false)

const activeStep = computed(() => {
  const idx = AGENT_ORDER.indexOf(store.currentAgent)
  return idx < 0 ? 0 : (store.finished ? AGENT_ORDER.length : idx)
})

function typeIcon(type: string): string {
  return { prd: '📋', design: '📐', code: '📄', test_report: '🧪' }[type] ?? '📄'
}

function downloadUrl(id: number): string {
  return `/api/artifacts/${id}/download?satoken=${encodeURIComponent(getToken())}`
}

async function refreshTask() {
  task.value = await api.getTask(taskId)
  artifacts.value = await api.listArtifacts(taskId)
}

async function onApprove(decision: 'pass' | 'reject') {
  if (decision === 'reject' && !comment.value.trim()) {
    ElMessage.warning('驳回时请填写意见')
    return
  }
  approving.value = true
  try {
    await api.approve(taskId, decision, comment.value.trim())
    store.clearGate()
    comment.value = ''
    await refreshTask()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    approving.value = false
  }
}

async function onCancel() {
  await ElMessageBox.confirm('确定取消该任务？Agent 将停止执行。', '取消任务', { type: 'warning' })
  await api.cancelTask(taskId)
  await refreshTask()
}

// 事件推进时同步任务状态与产物（interrupt/终态/产物事件都会引起变化）
watch(() => store.events.length, async () => {
  const last = store.events[store.events.length - 1]
  if (last && ['interrupt', 'task_done', 'task_failed', 'artifact_created'].includes(last.event)) {
    await refreshTask()
  }
})

onMounted(async () => {
  await refreshTask()
  await store.connect(taskId)
  loading.value = false
})

onUnmounted(() => store.disconnect())
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.head-right {
  display: flex;
  gap: 12px;
  align-items: center;
}
.req {
  color: #666;
}
.msg {
  margin-bottom: 16px;
}
.msg-head {
  font-weight: 600;
  margin-bottom: 6px;
}
.msg-body {
  background: #f8f9fb;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}
.gate-card :deep(.el-card__header) {
  background: #fdf6ec;
  font-weight: 600;
}
.artifact {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
}
</style>
