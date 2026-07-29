<template>
  <div>
    <div class="page-head">
      <div>
        <el-button text @click="router.push('/projects')">← 返回项目</el-button>
        <h2 style="margin-top:6px">模型对比</h2>
        <div class="sub">同一需求 · 两个模型各跑一遍</div>
      </div>
    </div>

    <el-row :gutter="20">
      <el-col v-for="col in cols" :key="col.id" :xs="24" :md="12">
        <el-card class="cmp-card">
          <template #header>
            <div class="cmp-head">
              <span class="cmp-title">任务 #{{ col.id }}</span>
              <span class="cmp-metrics">
                <span :class="statusClass(col.status)">{{ statusText(col.status) }}</span>
                <span v-if="col.tokens" class="meta-chip">{{ fmtTokens(col.tokens) }} tokens</span>
              </span>
            </div>
          </template>

          <div class="cmp-stream">
            <div v-for="(m, i) in col.messages" :key="i" class="cmp-msg">
              <div class="cmp-agent">
                <span v-if="AGENT_META[m.agent]" class="agent-avatar mini">
                  <img :src="AGENT_META[m.agent].avatar" :alt="m.agent" />
                </span>
                {{ AGENT_META[m.agent]?.name ?? m.agent }}
              </div>
              <div class="md-body chat-bubble" :class="m.agent" v-html="renderMd(m.content)" />
            </div>
            <div v-if="col.messages.length === 0" class="cmp-wait">
              <el-icon class="spin"><Loading /></el-icon> AI 小队工作中…
            </div>
          </div>

          <div v-if="col.artifacts.length" class="cmp-arts">
            <span class="arts-label">产物：</span>
            <el-tag v-for="a in col.artifacts" :key="a.id" size="small" effect="plain"
                    style="margin-right:6px">{{ a.name }}</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <div class="cmp-verdict sticker-card" v-if="bothDone">
      <div class="verdict-title">对比小结</div>
      <div class="verdict-body">
        两个任务均已完成。左侧消耗 <b>{{ fmtTokens(cols[0].tokens) }}</b> tokens，
        右侧消耗 <b>{{ fmtTokens(cols[1].tokens) }}</b> tokens。
        产物数量：{{ cols[0].artifacts.length }} vs {{ cols[1].artifacts.length }}。
        可点开各自任务详情页查看完整代码与下载。
      </div>
      <div style="margin-top:10px">
        <el-button size="small" @click="router.push(`/tasks/${cols[0].id}`)">查看左侧详情</el-button>
        <el-button size="small" @click="router.push(`/tasks/${cols[1].id}`)">查看右侧详情</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { api, type Artifact, type Task } from '../api'
import { AGENT_META, statusClass, statusText } from '../utils/status'

const route = useRoute()
const router = useRouter()
const idA = Number(route.params.idA)
const idB = Number(route.params.idB)

interface Col {
  id: number
  status: Task['status']
  messages: { agent: string; content: string }[]
  artifacts: Artifact[]
  tokens: number
  seq: number
  done: boolean
}

function newCol(id: number): Col {
  return { id, status: 'running', messages: [], artifacts: [], tokens: 0, seq: 0, done: false }
}

const cols = reactive<Col[]>([newCol(idA), newCol(idB)])
const bothDone = computed(() => cols[0].done && cols[1].done)

let timer: number | undefined

function renderMd(content: string): string {
  return DOMPurify.sanitize(marked.parse(content, { async: false }) as string)
}

function fmtTokens(n: number): string {
  return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n)
}

const TERMINAL = ['done', 'failed', 'canceled']

async function poll(col: Col) {
  if (col.done) return
  try {
    const task = await api.getTask(col.id)
    col.status = task.status
    const events = await api.listEvents(col.id, col.seq)
    for (const e of events) {
      col.seq = Math.max(col.seq, e.seq)
      const data = typeof e.data === 'string' ? safeParse(e.data) : e.data
      if (e.event === 'agent_message' && e.agent) {
        col.messages.push({ agent: e.agent, content: String((data as Record<string, unknown>).content ?? '') })
      }
      const tin = (data as Record<string, unknown>).input_tokens
      const tout = (data as Record<string, unknown>).output_tokens
      if (tin !== undefined) col.tokens = Number(tin) + Number(tout ?? 0)
    }
    if (TERMINAL.includes(task.status)) {
      col.artifacts = await api.listArtifacts(col.id)
      col.done = true
    }
  } catch {
    // 网络抖动忽略，下次轮询重试
  }
}

function safeParse(s: string): Record<string, unknown> {
  try {
    return JSON.parse(s)
  } catch {
    return {}
  }
}

onMounted(() => {
  timer = window.setInterval(() => {
    if (!bothDone.value) {
      poll(cols[0])
      poll(cols[1])
    }
  }, 2000)
  poll(cols[0])
  poll(cols[1])
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<style scoped>
.cmp-card { margin-bottom: 20px; }
.cmp-head { display: flex; justify-content: space-between; align-items: center; }
.cmp-title { font-weight: 700; }
.cmp-metrics { display: inline-flex; gap: 8px; align-items: center; }
.cmp-stream { max-height: 60vh; overflow-y: auto; }
.cmp-msg { margin-bottom: 14px; }
.cmp-agent { display: inline-flex; align-items: center; gap: 6px; font-weight: 700; font-size: 13px; margin-bottom: 5px; }
.cmp-wait { text-align: center; color: hsl(250 12% 55%); padding: 30px 0; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.cmp-arts { margin-top: 12px; padding-top: 10px; border-top: 1.5px dashed hsl(var(--c-ink) / .15); }
.arts-label { font-size: 12px; color: hsl(250 12% 55%); }
.agent-avatar.mini { width: 22px; height: 22px; }
.agent-avatar.mini img { width: 100%; height: 100%; border-radius: 50%; }
.cmp-verdict { padding: 16px 20px; margin-top: 4px; }
.verdict-title { font-family: var(--font-display); font-size: 16px; margin-bottom: 6px; }
.verdict-body { font-size: 13.5px; line-height: 1.7; }
</style>
