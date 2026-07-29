<template>
  <div v-if="task">
    <el-button class="back-btn" text @click="router.push('/projects')">← 返回项目</el-button>
    <div class="head sticker-card">
      <div class="head-left">
        <div class="task-badge">#{{ task.id }}</div>
        <div>
          <div class="task-title">{{ task.requirement }}</div>
          <div class="meta-row">
            <span class="meta-chip">{{ task.autoMode ? '全自动模式' : '人工审批模式' }}</span>
            <span v-if="task.currentNode" class="meta-chip">环节：{{ nodeLabel(task.currentNode) }}</span>
            <span class="meta-chip">创建于 {{ fmtTime(task.createdAt) }}</span>
            <span v-if="store.tokenUsage" class="meta-chip">
              消耗 {{ fmtTokens(store.tokenUsage.input + store.tokenUsage.output) }} tokens
            </span>
          </div>
        </div>
      </div>
      <div class="head-right">
        <span v-if="task.status === 'running'" class="pulse-dot" />
        <span :class="statusClass(task.status)" style="font-size:14px">{{ statusText(task.status) }}</span>
        <el-button v-if="task.status === 'failed'" type="primary" :loading="retrying"
                   @click="onRetry">断点重试</el-button>
        <el-button v-if="!isTerminal" type="danger" @click="onCancel">取消任务</el-button>
      </div>
    </div>

    <el-row :gutter="20" style="margin-top:20px">
      <!-- 左侧：角色流水线 -->
      <el-col :span="5">
        <el-card>
          <template #header>
            <div class="crew-head">
              <span>AI 小队</span>
              <span class="crew-progress-text">{{ activeStep }}/{{ AGENT_ORDER.length }}</span>
            </div>
          </template>
          <div class="progress-track" style="margin-bottom:14px">
            <div class="progress-fill" :style="{ width: (activeStep / AGENT_ORDER.length * 100) + '%' }" />
          </div>
          <div v-for="(r, i) in AGENT_ORDER" :key="r" class="crew-row"
               :class="{ active: r === store.currentAgent && !store.finished, done: i < activeStep }">
            <div class="agent-avatar" :class="{ wiggle: r === store.currentAgent && !store.finished }">
              <img :src="AGENT_META[r].avatar" :alt="AGENT_META[r].name" />
            </div>
            <div class="crew-info">
              <div class="crew-name">{{ AGENT_META[r].name }}</div>
              <div class="crew-state">
                {{ i < activeStep ? '完成' : (r === store.currentAgent && !store.finished ? '工作中' : '待命') }}
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 中间：事件流（聊天气泡） -->
      <el-col :span="12">
        <el-card v-loading="loading">
          <template #header>协作实况</template>
          <div ref="streamBody" class="stream-body">
            <div v-if="store.messages.length === 0" class="waiting">
              <img src="../assets/logo.png" alt="思考中" class="waiting-logo float-soft" />
              <div>小队正在思考，稍等片刻</div>
            </div>
            <div v-for="m in store.messages" :key="m.seq" class="msg pop-in">
              <div class="agent-avatar">
                <img :src="AGENT_META[m.agent]?.avatar" :alt="m.agent" />
              </div>
              <div class="msg-main">
                <div class="msg-name">{{ AGENT_META[m.agent]?.name ?? m.agent }}</div>
                <div :class="['chat-bubble', 'md-body', m.agent]" v-html="renderMd(m.content)" />
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：审批 + 产物 -->
      <el-col :span="7">
        <el-card v-if="store.pendingGate" class="gate-card pop-in">
          <template #header>{{ GATE_TEXT[store.pendingGate.gate] ?? store.pendingGate.question }}</template>
          <el-input v-model="comment" type="textarea" :rows="3" placeholder="审批意见（驳回时必填）" />
          <el-select v-if="rejectOptions.length" v-model="rejectTarget" size="small"
                     style="width:100%;margin-top:10px" placeholder="驳回后回退到…">
            <el-option v-for="o in rejectOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
          <div style="margin-top:14px;display:flex;gap:10px">
            <el-button type="success" style="flex:1" :loading="approving" @click="onApprove('pass')">通过</el-button>
            <el-button type="danger" style="flex:1" :loading="approving" @click="onApprove('reject')">驳回</el-button>
          </div>
        </el-card>

        <!-- 多轮迭代：完成后可继续提修改意见 -->
        <el-card v-if="task.status === 'done'" class="iterate-card pop-in">
          <template #header>继续迭代</template>
          <el-input v-model="iterateFeedback" type="textarea" :rows="3"
                    placeholder="对当前产物提出修改意见，例如：把除法改成返回分数" />
          <el-button type="primary" style="width:100%;margin-top:12px" :loading="iterating"
                     @click="onIterate">让小队继续修改</el-button>
        </el-card>

        <el-card :style="store.pendingGate ? 'margin-top:20px' : ''">
          <template #header>
            <div class="artifact-head">
              <span>任务产物（{{ artifacts.length }}）</span>
              <el-link v-if="artifacts.length" type="primary" :href="zipUrl" target="_blank">打包下载</el-link>
            </div>
          </template>
          <div v-if="artifacts.length === 0" class="no-artifact">还没有产出，敬请期待</div>
          <div v-for="a in artifacts" :key="a.id" class="artifact">
            <span class="artifact-name">
              <span class="type-chip">{{ ARTIFACT_TYPE_TEXT[a.type] ?? a.type }}</span>{{ a.name }}
            </span>
            <span class="artifact-ops">
              <el-link type="primary" @click="onPreview(a)">预览</el-link>
              <el-link type="primary" :href="downloadUrl(a.id)" target="_blank">下载</el-link>
            </span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 产物预览抽屉 -->
    <el-drawer v-model="previewOpen" :title="previewName" size="52%">
      <div v-if="previewIsMd" class="md-body" v-html="renderMd(previewContent)" />
      <pre v-else class="preview-code">{{ previewContent }}</pre>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { api, type Artifact, type Task } from '../api'
import { getToken } from '../api/http'
import { useTaskEventsStore } from '../stores/taskEvents'
import { AGENT_META, AGENT_ORDER, ARTIFACT_TYPE_TEXT, GATE_REJECT_TARGETS, GATE_TEXT, statusClass, statusText } from '../utils/status'

const route = useRoute()
const router = useRouter()
const store = useTaskEventsStore()
const taskId = Number(route.params.id)
const task = ref<Task | null>(null)
const artifacts = ref<Artifact[]>([])
const comment = ref('')
const approving = ref(false)
const loading = ref(true)
const streamBody = ref<HTMLElement>()
const retrying = ref(false)
const iterating = ref(false)
const iterateFeedback = ref('')
const rejectTarget = ref('')
const previewOpen = ref(false)
const previewName = ref('')
const previewContent = ref('')
const previewIsMd = ref(false)

const zipUrl = computed(() =>
  `/api/tasks/${taskId}/artifacts/zip?satoken=${encodeURIComponent(getToken())}`)

const rejectOptions = computed(() =>
  store.pendingGate ? (GATE_REJECT_TARGETS[store.pendingGate.gate] ?? []) : [])

const isLive = computed(() =>
  task.value ? ['running', 'waiting_review'].includes(task.value.status) : false)

const isTerminal = computed(() =>
  task.value ? ['done', 'failed', 'canceled'].includes(task.value.status) : false)

const activeStep = computed(() => {
  const idx = AGENT_ORDER.indexOf(store.currentAgent)
  return idx < 0 ? 0 : (store.finished ? AGENT_ORDER.length : idx)
})

/** Agent 产出按 Markdown 渲染（DOMPurify 消毒防注入）。 */
function renderMd(content: string): string {
  return DOMPurify.sanitize(marked.parse(content, { async: false }) as string)
}

function nodeLabel(node: string): string {
  return GATE_TEXT[node] ?? AGENT_META[node]?.name ?? node
}

function fmtTime(s: string): string {
  return s ? s.slice(0, 16).replace('T', ' ') : ''
}

function fmtTokens(n: number): string {
  return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n)
}

async function onRetry() {
  retrying.value = true
  try {
    await api.retryTask(taskId)
    await refreshTask()
    await store.connect(taskId, true)   // 重试后强制订阅
    ElMessage.success('已从断点继续执行')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    retrying.value = false
  }
}

async function onPreview(a: Artifact) {
  try {
    previewContent.value = await api.artifactContent(a.id)
    previewName.value = a.name
    previewIsMd.value = a.name.toLowerCase().endsWith('.md')
    previewOpen.value = true
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
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
    await api.approve(taskId, decision, comment.value.trim(),
      decision === 'reject' && rejectTarget.value ? rejectTarget.value : undefined)
    store.clearGate()
    comment.value = ''
    rejectTarget.value = ''
    await refreshTask()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    approving.value = false
  }
}

async function onIterate() {
  if (!iterateFeedback.value.trim()) {
    ElMessage.warning('请填写修改意见')
    return
  }
  iterating.value = true
  try {
    await api.iterateTask(taskId, iterateFeedback.value.trim())
    iterateFeedback.value = ''
    await refreshTask()
    await store.connect(taskId, true)   // 新一轮强制订阅
    ElMessage.success('小队已开始新一轮修改')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    iterating.value = false
  }
}

async function onCancel() {
  await ElMessageBox.confirm('确定取消该任务？AI 小队会停止工作。', '取消任务', { type: 'warning' })
  await api.cancelTask(taskId)
  await refreshTask()
}

// 新消息自动滚到底部，保持跟读体验
watch(() => store.messages.length, async () => {
  await nextTick()
  streamBody.value?.scrollTo({ top: streamBody.value.scrollHeight, behavior: 'smooth' })
})

// 事件推进时同步任务状态与产物（interrupt/终态/产物事件都会引起变化）
watch(() => store.events.length, async () => {
  const last = store.events[store.events.length - 1]
  if (last && ['interrupt', 'task_done', 'task_failed', 'artifact_created'].includes(last.event)) {
    await refreshTask()
  }
})

onMounted(async () => {
  await refreshTask()
  await store.connect(taskId, isLive.value)   // 任务未终止则强制订阅（历史含旧终态也照连）
  loading.value = false
})

onUnmounted(() => store.disconnect())
</script>

<style scoped>
.back-btn {
  margin-bottom: 12px;
  font-size: 14px;
  color: hsl(var(--c-primary-deep));
}
.stream-body {
  max-height: 62vh;
  overflow: auto;
  padding-right: 4px;
}
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
.meta-row { display: flex; gap: 8px; margin-top: 6px; flex-wrap: wrap; }
.crew-head { display: flex; justify-content: space-between; align-items: center; }
.crew-progress-text {
  font-size: 13px;
  color: hsl(var(--c-primary-deep));
  font-family: var(--font-display);
}
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
.waiting-logo {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  border: var(--border-cartoon);
  box-shadow: 3px 3px 0 hsl(var(--c-ink) / .85);
  object-fit: cover;
  margin-bottom: 10px;
}
.msg { display: flex; gap: 12px; margin-bottom: 18px; }
.msg-main { flex: 1; min-width: 0; }
.msg-name { font-weight: 700; font-size: 13px; margin-bottom: 5px; }

/* 审批卡 */
.gate-card :deep(.el-card__header) {
  background: hsl(var(--c-yellow) / .16);
}
.iterate-card :deep(.el-card__header) {
  background: hsl(var(--c-primary) / .1);
}
.iterate-card { margin-top: 20px; }
.iterate-card:first-child { margin-top: 0; }

/* 产物 */
.artifact-head { display: flex; justify-content: space-between; align-items: center; }
.artifact-ops { display: inline-flex; gap: 10px; flex: none; }
.preview-code {
  background: hsl(220 35% 15%);
  color: hsl(210 40% 96%);
  padding: 14px 16px;
  border-radius: 12px;
  overflow: auto;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
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
.artifact-name {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}
.type-chip {
  flex: none;
  font-size: 11px;
  font-weight: 700;
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  border: 1.5px solid hsl(var(--c-ink) / .7);
  background: hsl(var(--c-paper));
  color: hsl(var(--c-primary-deep));
}
</style>
