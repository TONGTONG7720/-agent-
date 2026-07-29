<template>
  <div v-if="task">
    <div class="head sticker-card">
      <div class="head-left">
        <div class="task-badge">#{{ task.id }}</div>
        <div>
          <div class="task-title">{{ task.requirement }}</div>
          <div class="task-sub">五只小机器人正在为这个需求努力工作中…</div>
        </div>
      </div>
      <div class="head-right">
        <span v-if="task.status === 'running'" class="pulse-dot" />
        <el-tag :type="statusType(task.status)" size="large">{{ statusText(task.status) }}</el-tag>
        <el-button v-if="!isTerminal" type="danger" @click="onCancel">🛑 取消任务</el-button>
      </div>
    </div>

    <el-row :gutter="20" style="margin-top:20px">
      <!-- 左侧：角色流水线 -->
      <el-col :span="5">
        <el-card>
          <template #header>👥 AI 小队</template>
          <div v-for="(r, i) in AGENT_ORDER" :key="r" class="crew-row"
               :class="{ active: r === store.currentAgent && !store.finished, done: i < activeStep }">
            <div class="agent-avatar" :class="[r, { wiggle: r === store.currentAgent && !store.finished }]">
              {{ AGENT_META[r].icon }}
            </div>
            <div class="crew-info">
              <div class="crew-name">{{ AGENT_META[r].name }}</div>
              <div class="crew-state">
                {{ i < activeStep ? '✅ 完成' : (r === store.currentAgent && !store.finished ? '💪 工作中' : '💤 待命') }}
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 中间：事件流（聊天气泡） -->
      <el-col :span="12">
        <el-card v-loading="loading" body-style="max-height:66vh;overflow:auto">
          <template #header>💬 协作直播间</template>
          <div v-if="store.messages.length === 0" class="waiting">
            <span class="wiggle" style="display:inline-block;font-size:34px">🤖</span>
            <div>小机器人们正在思考，稍等一下下…</div>
          </div>
          <div v-for="m in store.messages" :key="m.seq" class="msg pop-in">
            <div class="agent-avatar" :class="m.agent">{{ AGENT_META[m.agent]?.icon ?? '🤖' }}</div>
            <div class="msg-main">
              <div class="msg-name">{{ AGENT_META[m.agent]?.name ?? m.agent }}</div>
              <pre class="chat-bubble msg-body">{{ m.content }}</pre>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：审批 + 产物 -->
      <el-col :span="7">
        <el-card v-if="store.pendingGate" class="gate-card pop-in">
          <template #header>
            <span class="wiggle" style="display:inline-block">⏸️</span>
            {{ GATE_TEXT[store.pendingGate.gate] ?? store.pendingGate.question }}
          </template>
          <el-input v-model="comment" type="textarea" :rows="3" placeholder="审批意见（驳回时必填）" />
          <div style="margin-top:14px;display:flex;gap:10px">
            <el-button type="success" style="flex:1" :loading="approving" @click="onApprove('pass')">👍 通过</el-button>
            <el-button type="danger" style="flex:1" :loading="approving" @click="onApprove('reject')">👎 驳回</el-button>
          </div>
        </el-card>

        <el-card :style="store.pendingGate ? 'margin-top:20px' : ''">
          <template #header>🎁 产物宝箱（{{ artifacts.length }}）</template>
          <div v-if="artifacts.length === 0" class="no-artifact">还没有产出，敬请期待～</div>
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
  await ElMessageBox.confirm('确定取消该任务？小机器人们会停止工作。', '取消任务', { type: 'warning' })
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
  align-items: center;
  padding: 16px 22px;
}
.head-left { display: flex; align-items: center; gap: 16px; }
.task-badge {
  background: var(--gradient-candy);
  color: hsl(var(--c-paper));
  border: 2px solid hsl(var(--c-ink));
  border-radius: 14px;
  box-shadow: 2.5px 2.5px 0 hsl(var(--c-ink) / .85);
  padding: 8px 14px;
  font-size: 18px;
  font-weight: 700;
}
.task-title { font-size: 17px; font-weight: 700; }
.task-sub { font-size: 12px; color: hsl(250 12% 55%); margin-top: 2px; }
.head-right { display: flex; gap: 12px; align-items: center; }

/* 左侧小队 */
.crew-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-radius: 14px;
  transition: var(--transition-bounce);
  opacity: .55;
}
.crew-row.active {
  opacity: 1;
  background: hsl(var(--c-primary) / .12);
  border: 2px dashed hsl(var(--c-primary));
}
.crew-row.done { opacity: 1; }
.crew-name { font-weight: 700; font-size: 14px; }
.crew-state { font-size: 12px; color: hsl(250 12% 52%); }

/* 中间气泡流 */
.waiting {
  text-align: center;
  color: hsl(250 12% 55%);
  padding: 40px 0;
}
.msg { display: flex; gap: 12px; margin-bottom: 18px; }
.msg-main { flex: 1; min-width: 0; }
.msg-name { font-weight: 700; font-size: 13px; margin-bottom: 5px; }
.msg-body {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  font-family: var(--el-font-family);
}

/* 审批卡 */
.gate-card :deep(.el-card__header) {
  background: hsl(var(--c-yellow) / .3);
}

/* 产物 */
.no-artifact { text-align: center; color: hsl(250 12% 55%); font-size: 13px; padding: 10px 0; }
.artifact {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  margin-bottom: 8px;
  background: hsl(var(--c-mint) / .1);
  border: 2px dashed hsl(var(--c-ink) / .25);
  border-radius: 12px;
}
</style>
